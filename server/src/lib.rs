use std::{
    path::Path,
    sync::Mutex,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use rusqlite::{Connection, OptionalExtension, TransactionBehavior, params};
use sha2::{Digest, Sha256};
use tonic::{Request, Response, Status};
use uuid::Uuid;

pub mod protocol {
    tonic::include_proto!("familyboard.v1");
}

use protocol::{family_board_server::FamilyBoard, *};
type Rpc<T> = Result<Response<T>, Status>;

pub struct FamilyService {
    database: Mutex<Connection>,
}

struct Membership {
    id: String,
    family_id: String,
}

impl FamilyService {
    pub fn open(path: impl AsRef<Path>) -> rusqlite::Result<Self> {
        let database = Connection::open(path)?;
        database.busy_timeout(Duration::from_secs(3))?;
        database.execute_batch(include_str!("schema.sql"))?;
        Ok(Self {
            database: Mutex::new(database),
        })
    }

    fn run<T>(&self, f: impl FnOnce(&Connection) -> Result<T, Status>) -> Rpc<T> {
        let database = self.database.lock().map_err(db_error)?;
        Ok(Response::new(f(&database)?))
    }

    fn write<T>(&self, f: impl FnOnce(&Connection) -> Result<T, Status>) -> Rpc<T> {
        let mut database = self.database.lock().map_err(db_error)?;
        let tx = database
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(db_error)?;
        let value = f(&tx)?;
        tx.commit().map_err(db_error)?;
        Ok(Response::new(value))
    }
}

#[tonic::async_trait]
impl FamilyBoard for FamilyService {
    async fn create_family(&self, request: Request<CreateFamilyRequest>) -> Rpc<Family> {
        let token_hash = authenticate(&request)?;
        let body = request.into_inner();
        let family_name = name(&body.family_name)?;
        let display_name = name(&body.display_name)?;
        self.write(|db| {
            if let Some(member) = membership(db, &token_hash)? {
                return family(db, &member);
            }
            let family_id = new_id();
            db.execute(
                "INSERT INTO families VALUES (?1, ?2)",
                params![family_id, family_name],
            )
            .map_err(db_error)?;
            let member = add_member(db, &family_id, display_name, &token_hash)?;
            family(db, &member)
        })
    }

    async fn create_invite(&self, request: Request<CreateInviteRequest>) -> Rpc<Invite> {
        let token_hash = authenticate(&request)?;
        self.write(|db| {
            let member = require_member(db, &token_hash)?;
            let code = format!("{}{}", Uuid::new_v4().simple(), Uuid::new_v4().simple());
            let expires = now() + 24 * 60 * 60;
            db.execute(
                "INSERT INTO invites (code_hash, family_id, expires_at) VALUES (?1, ?2, ?3)",
                params![hash(&code), member.family_id, expires],
            )
            .map_err(db_error)?;
            Ok(Invite {
                code,
                expires_unix_seconds: expires,
            })
        })
    }

    async fn join_family(&self, request: Request<JoinFamilyRequest>) -> Rpc<Family> {
        let token_hash = authenticate(&request)?;
        let body = request.into_inner();
        let display_name = name(&body.display_name)?;
        let code = body.invite_code.trim().to_ascii_lowercase();
        if !valid_hex_secret(&code) {
            return Err(Status::invalid_argument("Invalid invitation format."));
        }
        let code_hash = hash(&code);
        self.write(|db| {
            let invite = db
                .query_row(
                    "SELECT family_id, expires_at, redeemed_by FROM invites WHERE code_hash = ?1",
                    [&code_hash[..]],
                    |row| {
                        Ok((
                            row.get::<_, String>(0)?,
                            row.get::<_, i64>(1)?,
                            row.get::<_, Option<String>>(2)?,
                        ))
                    },
                )
                .optional()
                .map_err(db_error)?;
            let Some((family_id, expires, redeemed_by)) = invite else {
                return Err(Status::not_found("Invitation unavailable."));
            };
            if let Some(member) = membership(db, &token_hash)? {
                if redeemed_by.as_deref() == Some(member.id.as_str()) {
                    return family(db, &member);
                }
                return Err(Status::failed_precondition(
                    "This device already belongs to a family.",
                ));
            }
            if redeemed_by.is_some() || expires <= now() {
                return Err(Status::not_found("Invitation unavailable."));
            }
            let member = add_member(db, &family_id, display_name, &token_hash)?;
            db.execute(
                "UPDATE invites SET redeemed_by = ?1 WHERE code_hash = ?2",
                params![member.id, code_hash],
            )
            .map_err(db_error)?;
            family(db, &member)
        })
    }

    async fn get_board(&self, request: Request<GetBoardRequest>) -> Rpc<Board> {
        let token_hash = authenticate(&request)?;
        self.run(|db| {
            let member = require_member(db, &token_hash)?;
            let mut statement = db.prepare(
                "SELECT alerts.id FROM alerts WHERE family_id = ?1 ORDER BY created_at DESC, rowid DESC LIMIT 50",
            ).map_err(db_error)?;
            let alerts = statement.query_map([&member.family_id], |row| row.get::<_, String>(0)).map_err(db_error)?
                .map(|id| alert(db, &member.family_id, &id.map_err(db_error)?))
                .collect::<Result<Vec<_>, _>>()?;
            Ok(Board { family: Some(family(db, &member)?), alerts })
        })
    }

    async fn send_alert(&self, request: Request<SendAlertRequest>) -> Rpc<Alert> {
        let token_hash = authenticate(&request)?;
        let request_id = Uuid::parse_str(&request.into_inner().request_id)
            .map_err(|_| Status::invalid_argument("Invalid request identifier."))?
            .to_string();
        self.write(|db| {
            let member = require_member(db, &token_hash)?;
            let existing = db
                .query_row(
                    "SELECT id FROM alerts WHERE sender_id = ?1 AND request_id = ?2",
                    params![member.id, request_id],
                    |row| row.get::<_, String>(0),
                )
                .optional()
                .map_err(db_error)?;
            let id = match existing {
                Some(id) => id,
                None => {
                    let id = new_id();
                    db.execute(
                        "INSERT INTO alerts VALUES (?1, ?2, ?3, ?4, ?5)",
                        params![id, member.family_id, member.id, request_id, now()],
                    )
                    .map_err(db_error)?;
                    id
                }
            };
            alert(db, &member.family_id, &id)
        })
    }

    async fn acknowledge_alert(&self, request: Request<AcknowledgeAlertRequest>) -> Rpc<Alert> {
        let token_hash = authenticate(&request)?;
        let id = request.into_inner().alert_id;
        self.write(|db| {
            let member = require_member(db, &token_hash)?;
            let current = alert(db, &member.family_id, &id)?;
            if current.sender.is_some_and(|sender| sender.id == member.id) {
                return Err(Status::failed_precondition("Only another family member can acknowledge this alert."));
            }
            db.execute(
                "INSERT INTO acknowledgements VALUES (?1, ?2, ?3) ON CONFLICT (alert_id, member_id) DO NOTHING",
                params![id, member.id, now()],
            ).map_err(db_error)?;
            alert(db, &member.family_id, &id)
        })
    }
}

fn db_error(_: impl std::fmt::Debug) -> Status {
    Status::internal("FamilyBoard database unavailable.")
}

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

fn new_id() -> String {
    Uuid::new_v4().to_string()
}

fn name(text: &str) -> Result<&str, Status> {
    let text = text.trim();
    if text.is_empty() || text.chars().count() > 40 || text.chars().any(char::is_control) {
        return Err(Status::invalid_argument("Names require 1–40 characters."));
    }
    Ok(text)
}

fn valid_hex_secret(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn hash(value: &str) -> [u8; 32] {
    Sha256::digest(value.as_bytes()).into()
}

fn authenticate<T>(request: &Request<T>) -> Result<[u8; 32], Status> {
    let token = request
        .metadata()
        .get("authorization")
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .filter(|value| valid_hex_secret(value))
        .ok_or_else(|| Status::unauthenticated("Device credential required."))?;
    Ok(hash(token))
}

fn membership(db: &Connection, token: &[u8; 32]) -> Result<Option<Membership>, Status> {
    db.query_row(
        "SELECT id, family_id FROM members WHERE token_hash = ?1",
        [&token[..]],
        |row| {
            Ok(Membership {
                id: row.get(0)?,
                family_id: row.get(1)?,
            })
        },
    )
    .optional()
    .map_err(db_error)
}

fn require_member(db: &Connection, token: &[u8; 32]) -> Result<Membership, Status> {
    membership(db, token)?.ok_or_else(|| Status::not_found("This device has no family."))
}

fn add_member(
    db: &Connection,
    family_id: &str,
    display_name: &str,
    token: &[u8; 32],
) -> Result<Membership, Status> {
    let id = new_id();
    db.execute(
        "INSERT INTO members VALUES (?1, ?2, ?3, ?4)",
        params![id, family_id, display_name, token],
    )
    .map_err(db_error)?;
    Ok(Membership {
        id,
        family_id: family_id.to_owned(),
    })
}

fn family(db: &Connection, member: &Membership) -> Result<Family, Status> {
    let name = db
        .query_row(
            "SELECT name FROM families WHERE id = ?1",
            [&member.family_id],
            |row| row.get(0),
        )
        .map_err(db_error)?;
    let mut statement = db
        .prepare("SELECT id, display_name FROM members WHERE family_id = ?1 ORDER BY rowid")
        .map_err(db_error)?;
    let members = statement
        .query_map([&member.family_id], |row| {
            Ok(Member {
                id: row.get(0)?,
                display_name: row.get(1)?,
            })
        })
        .map_err(db_error)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(db_error)?;
    Ok(Family {
        id: member.family_id.clone(),
        name,
        my_member_id: member.id.clone(),
        members,
    })
}

fn alert(db: &Connection, family_id: &str, id: &str) -> Result<Alert, Status> {
    let (created_unix_seconds, sender) = db.query_row(
        "SELECT alerts.created_at, members.id, members.display_name FROM alerts
         JOIN members ON alerts.sender_id = members.id WHERE alerts.family_id = ?1 AND alerts.id = ?2",
        params![family_id, id],
        |row| Ok((row.get(0)?, Member { id: row.get(1)?, display_name: row.get(2)? })),
    ).optional().map_err(db_error)?.ok_or_else(|| Status::not_found("Alert unavailable."))?;
    let mut statement = db.prepare(
        "SELECT acknowledgements.acknowledged_at, members.id, members.display_name FROM acknowledgements
         JOIN members ON acknowledgements.member_id = members.id
         WHERE alert_id = ?1 ORDER BY acknowledgements.acknowledged_at, members.id",
    ).map_err(db_error)?;
    let acknowledgements = statement
        .query_map([id], |row| {
            Ok(Acknowledgement {
                acknowledged_unix_seconds: row.get(0)?,
                member: Some(Member {
                    id: row.get(1)?,
                    display_name: row.get(2)?,
                }),
            })
        })
        .map_err(db_error)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(db_error)?;
    Ok(Alert {
        id: id.to_owned(),
        sender: Some(sender),
        created_unix_seconds,
        acknowledgements,
    })
}
