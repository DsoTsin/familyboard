use std::{env, fs, net::SocketAddr, time::Duration};

use familyboard_server::{FamilyService, protocol::family_board_server::FamilyBoardServer};
use tonic::transport::{Identity, Server, ServerTlsConfig};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let address: SocketAddr = env::var("FAMILYBOARD_BIND")
        .unwrap_or_else(|_| "127.0.0.1:8443".into())
        .parse()?;
    let database = env::var("FAMILYBOARD_DB").unwrap_or_else(|_| "familyboard.sqlite".into());
    let cert = fs::read(env::var("FAMILYBOARD_TLS_CERT")?)?;
    let key = fs::read(env::var("FAMILYBOARD_TLS_KEY")?)?;
    let service = FamilyService::open(database)?;
    let server = Server::builder()
        .tls_config(ServerTlsConfig::new().identity(Identity::from_pem(cert, key)))?
        .timeout(Duration::from_secs(10))
        .add_service(FamilyBoardServer::new(service).max_decoding_message_size(4096));
    eprintln!("FamilyBoard listening for TLS gRPC on {address}");
    server
        .serve_with_shutdown(address, async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await?;
    Ok(())
}
