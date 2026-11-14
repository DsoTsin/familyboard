use familyboard_server::{
    FamilyService,
    protocol::{family_board_client::FamilyBoardClient, family_board_server::FamilyBoardServer, *},
};
use rcgen::{CertifiedKey, generate_simple_self_signed};
use tokio::net::TcpListener;
use tokio_stream::wrappers::TcpListenerStream;
use tonic::{
    Code, Request,
    transport::{Certificate, ClientTlsConfig, Endpoint, Identity, Server, ServerTlsConfig},
};
use uuid::Uuid;

fn authorized<T>(value: T, device: &str) -> Request<T> {
    let mut request = Request::new(value);
    request
        .metadata_mut()
        .insert("authorization", format!("Bearer {device}").parse().unwrap());
    request
}

#[tokio::test]
async fn two_devices_can_post_and_confirm_a_family_alert_over_tls() {
    let CertifiedKey { cert, signing_key } =
        generate_simple_self_signed(vec!["localhost".into()]).unwrap();
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let directory = tempfile::tempdir().unwrap();
    let service = FamilyService::open(directory.path().join("test.sqlite")).unwrap();
    let server = Server::builder()
        .tls_config(
            ServerTlsConfig::new()
                .identity(Identity::from_pem(cert.pem(), signing_key.serialize_pem())),
        )
        .unwrap()
        .add_service(FamilyBoardServer::new(service));
    let task = tokio::spawn(async move {
        server
            .serve_with_incoming(TcpListenerStream::new(listener))
            .await
            .unwrap();
    });
    let channel = Endpoint::from_shared(format!("https://{address}"))
        .unwrap()
        .tls_config(
            ClientTlsConfig::new()
                .domain_name("localhost")
                .ca_certificate(Certificate::from_pem(cert.pem())),
        )
        .unwrap()
        .connect()
        .await
        .unwrap();
    let mut client = FamilyBoardClient::new(channel);
    let sender = "a".repeat(64);
    let recipient = "b".repeat(64);
    let other = "c".repeat(64);

    assert_eq!(
        client
            .get_board(GetBoardRequest {})
            .await
            .unwrap_err()
            .code(),
        Code::Unauthenticated
    );
    let family = client
        .create_family(authorized(
            CreateFamilyRequest {
                family_name: "Our family".into(),
                display_name: "Alex".into(),
            },
            &sender,
        ))
        .await
        .unwrap()
        .into_inner();
    let invite = client
        .create_invite(authorized(CreateInviteRequest {}, &sender))
        .await
        .unwrap()
        .into_inner();
    let join = JoinFamilyRequest {
        invite_code: invite.code,
        display_name: "Sam".into(),
    };
    let joined = client
        .join_family(authorized(join.clone(), &recipient))
        .await
        .unwrap()
        .into_inner();
    assert_eq!(joined.id, family.id);
    assert_eq!(joined.members.len(), 2);
    assert_eq!(
        client
            .join_family(authorized(join, &other))
            .await
            .unwrap_err()
            .code(),
        Code::NotFound
    );

    let help = SendAlertRequest {
        request_id: Uuid::new_v4().to_string(),
    };
    let posted = client
        .send_alert(authorized(help.clone(), &sender))
        .await
        .unwrap()
        .into_inner();
    let retry = client
        .send_alert(authorized(help, &sender))
        .await
        .unwrap()
        .into_inner();
    assert_eq!(posted.id, retry.id);

    client
        .create_family(authorized(
            CreateFamilyRequest {
                family_name: "Other family".into(),
                display_name: "Taylor".into(),
            },
            &other,
        ))
        .await
        .unwrap();
    let acknowledgement = AcknowledgeAlertRequest {
        alert_id: posted.id.clone(),
    };
    assert_eq!(
        client
            .acknowledge_alert(authorized(acknowledgement.clone(), &other))
            .await
            .unwrap_err()
            .code(),
        Code::NotFound
    );
    assert_eq!(
        client
            .acknowledge_alert(authorized(acknowledgement.clone(), &sender))
            .await
            .unwrap_err()
            .code(),
        Code::FailedPrecondition
    );
    for _ in 0..2 {
        let alert = client
            .acknowledge_alert(authorized(acknowledgement.clone(), &recipient))
            .await
            .unwrap()
            .into_inner();
        assert_eq!(alert.acknowledgements.len(), 1);
        assert_eq!(
            alert.acknowledgements[0].member.as_ref().unwrap().id,
            joined.my_member_id
        );
    }
    let board = client
        .get_board(authorized(GetBoardRequest {}, &sender))
        .await
        .unwrap()
        .into_inner();
    assert_eq!(board.alerts.len(), 1);
    assert_eq!(board.alerts[0].acknowledgements.len(), 1);
    task.abort();
}
