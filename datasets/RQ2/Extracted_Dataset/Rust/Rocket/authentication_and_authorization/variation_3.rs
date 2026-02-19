/*
--- CARGO.TOML ---
[dependencies]
rocket = { version = "0.5.0", features = ["json", "secrets"] }
serde = { version = "1.0", features = ["derive"] }
uuid = { version = "1.6", features = ["v4", "serde"] }
chrono = { version = "0.4", features = ["serde"] }
jsonwebtoken = "8.3"
bcrypt = "0.15"
oauth2 = "4.4"
reqwest = "0.11"
once_cell = "1.18"
dashmap = "5.5"
*/

#[macro_use]
extern crate rocket;

use rocket::{
    http::{CookieJar, Status},
    request::{FromRequest, Outcome, Request},
    response::{Redirect, Flash},
    serde::json::{json, Json, Value},
    State,
};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use chrono::{DateTime, Utc};
use std::collections::HashMap;
use once_cell::sync::Lazy;
use dashmap::DashMap;
use std::marker::PhantomData;
use jsonwebtoken::{decode, encode, DecodingKey, EncodingKey, Header, Validation};
use oauth2::{
    basic::BasicClient, AuthUrl, ClientId, ClientSecret, CsrfToken, RedirectUrl, Scope,
    TokenResponse, TokenUrl,
};

// --- DOMAIN & DB ---
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
pub enum Role { USER, ADMIN }
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum Status { DRAFT, PUBLISHED }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct User {
    pub id: Uuid,
    pub email: String,
    #[serde(skip_serializing)]
    pub password_hash: String,
    pub role: Role,
    pub is_active: bool,
    pub created_at: DateTime<Utc>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Post {
    pub id: Uuid,
    pub user_id: Uuid,
    pub title: String,
    pub content: String,
    pub status: Status,
}

type Db<K, V> = Lazy<DashMap<K, V>>;
static USERS: Db<Uuid, User> = Lazy::new(DashMap::new);
static POSTS: Db<Uuid, Post> = Lazy::new(DashMap::new);

// --- AUTH UTILITIES ---
mod auth_utils {
    use super::*;
    
    #[derive(Debug, Serialize, Deserialize)]
    pub struct Claims {
        pub sub: String,
        pub role: Role,
        pub exp: usize,
    }

    pub fn create_jwt(uid: Uuid, role: &Role, secret: &str) -> String {
        let exp = (Utc::now() + chrono::Duration::days(1)).timestamp() as usize;
        let claims = Claims { sub: uid.to_string(), role: role.clone(), exp };
        encode(&Header::default(), &claims, &EncodingKey::from_secret(secret.as_ref())).unwrap()
    }

    pub fn decode_jwt(token: &str, secret: &str) -> Option<Claims> {
        decode::<Claims>(token, &DecodingKey::from_secret(secret.as_ref()), &Validation::default())
            .map(|data| data.claims)
            .ok()
    }
}

// --- GENERIC ROLE-BASED GUARD ---
pub trait RoleCheck {
    const MIN_ROLE: Role;
}

pub struct UserRole;
impl RoleCheck for UserRole { const MIN_ROLE: Role = Role::USER; }

pub struct AdminRole;
impl RoleCheck for AdminRole { const MIN_ROLE: Role = Role::ADMIN; }

// The actual request guard
pub struct Auth<R: RoleCheck>(pub User, PhantomData<R>);

#[rocket::async_trait]
impl<'r, R: RoleCheck + Send + Sync> FromRequest<'r> for Auth<R> {
    type Error = Value;

    async fn from_request(req: &'r Request<'_>) -> Outcome<Self, Self::Error> {
        let secret = req.guard::<&State<AppConfig>>().await.unwrap().jwt_secret.clone();
        
        let token = match req.headers().get_one("Authorization").and_then(|v| v.strip_prefix("Bearer ")) {
            Some(t) => t,
            None => return Outcome::Failure((Status::Unauthorized, json!({"err": "missing_token"}))),
        };

        let claims = match auth_utils::decode_jwt(token, &secret) {
            Some(c) => c,
            None => return Outcome::Failure((Status::Unauthorized, json!({"err": "invalid_token"}))),
        };

        if claims.role < R::MIN_ROLE {
            return Outcome::Failure((Status::Forbidden, json!({"err": "insufficient_permissions"})));
        }

        let user_id = Uuid::parse_str(&claims.sub).unwrap();
        match USERS.get(&user_id) {
            Some(usr) if usr.is_active => Outcome::Success(Auth(usr.clone(), PhantomData)),
            _ => Outcome::Failure((Status::Unauthorized, json!({"err": "user_not_found"}))),
        }
    }
}

// --- API ENDPOINTS ---
#[derive(Deserialize)]
struct LoginData<'r> {
    email: &'r str,
    password: &'r str,
}

#[post("/login", data = "<login_data>")]
fn login(cfg: &State<AppConfig>, login_data: Json<LoginData<'_>>) -> Result<Value, Status> {
    let user = USERS.iter()
        .find(|entry| entry.value().email == login_data.email)
        .map(|entry| entry.value().clone());

    if let Some(u) = user {
        if bcrypt::verify(login_data.password, &u.password_hash).unwrap_or(false) {
            let token = auth_utils::create_jwt(u.id, &u.role, &cfg.jwt_secret);
            return Ok(json!({ "token": token }));
        }
    }
    Err(Status::Unauthorized)
}

#[get("/me")]
fn me(auth: Auth<UserRole>) -> Json<User> {
    Json(auth.0)
}

#[derive(Deserialize)]
struct NewPost {
    title: String,
    content: String,
}

#[post("/posts", data = "<data>")]
fn create_post(auth: Auth<UserRole>, data: Json<NewPost>) -> (Status, Json<Post>) {
    let post = Post {
        id: Uuid::new_v4(),
        user_id: auth.0.id,
        title: data.title.clone(),
        content: data.content.clone(),
        status: Status::DRAFT,
    };
    POSTS.insert(post.id, post.clone());
    (Status::Created, Json(post))
}

#[get("/posts")]
fn get_posts(_auth: Auth<UserRole>) -> Json<Vec<Post>> {
    Json(POSTS.iter().map(|e| e.value().clone()).collect())
}

#[delete("/posts/<id>")]
fn delete_post(_auth: Auth<AdminRole>, id: Uuid) -> Status {
    if POSTS.remove(&id).is_some() {
        Status::NoContent
    } else {
        Status::NotFound
    }
}

// --- OAUTH2 ---
fn get_oauth_client(cfg: &State<AppConfig>) -> BasicClient {
    BasicClient::new(
        ClientId::new(cfg.oauth_client_id.clone()),
        Some(ClientSecret::new(cfg.oauth_client_secret.clone())),
        AuthUrl::new("https://accounts.google.com/o/oauth2/v2/auth".to_string()).unwrap(),
        Some(TokenUrl::new("https://www.googleapis.com/oauth2/v4/token".to_string()).unwrap()),
    )
    .set_redirect_uri(RedirectUrl::new("http://localhost:8000/auth/google/callback".to_string()).unwrap())
}

#[get("/auth/google")]
fn oauth_login(cfg: &State<AppConfig>, cookies: &CookieJar<'_>) -> Redirect {
    let client = get_oauth_client(cfg);
    let (url, state) = client.authorize_url(CsrfToken::new_random).url();
    cookies.add(("csrf", state.secret().clone()));
    Redirect::to(url)
}

#[derive(Deserialize)]
struct CallbackParams { code: String, state: String }

#[get("/auth/google/callback?<params>")]
async fn oauth_callback(cfg: &State<AppConfig>, cookies: &CookieJar<'_>, params: CallbackParams) -> Result<Value, Flash<Redirect>> {
    if cookies.get("csrf").map_or(true, |c| c.value() != params.state) {
        return Err(Flash::error(Redirect::to("/"), "CSRF failed."));
    }
    cookies.remove("csrf");

    let client = get_oauth_client(cfg);
    if client.exchange_code(oauth2::AuthorizationCode::new(params.code))
        .request_async(oauth2::reqwest::async_http_client).await.is_ok() {
        // Mock: find user by email "user@example.com" and issue a token
        let user = USERS.iter().find(|u| u.email == "user@example.com").unwrap();
        let token = auth_utils::create_jwt(user.id, &user.role, &cfg.jwt_secret);
        Ok(json!({ "message": "OAuth login successful (mocked)", "token": token }))
    } else {
        Err(Flash::error(Redirect::to("/"), "Token exchange failed."))
    }
}

// --- APP SETUP ---
struct AppConfig {
    jwt_secret: String,
    oauth_client_id: String,
    oauth_client_secret: String,
}

fn init_db() {
    let admin_id = Uuid::new_v4();
    let user_id = Uuid::new_v4();
    USERS.insert(admin_id, User {
        id: admin_id,
        email: "admin@example.com".to_string(),
        password_hash: bcrypt::hash("admin123", 12).unwrap(),
        role: Role::ADMIN,
        is_active: true,
        created_at: Utc::now(),
    });
    USERS.insert(user_id, User {
        id: user_id,
        email: "user@example.com".to_string(),
        password_hash: bcrypt::hash("user123", 12).unwrap(),
        role: Role::USER,
        is_active: true,
        created_at: Utc::now(),
    });
}

#[launch]
fn rocket() -> _ {
    init_db();
    rocket::build()
        .manage(AppConfig {
            jwt_secret: "a_very_secret_key_for_jwt_3".to_string(),
            oauth_client_id: std::env::var("GOOGLE_CLIENT_ID").unwrap_or_else(|_| "test_id".to_string()),
            oauth_client_secret: std::env::var("GOOGLE_CLIENT_SECRET").unwrap_or_else(|_| "test_secret".to_string()),
        })
        .mount("/", routes![
            login,
            me,
            create_post,
            get_posts,
            delete_post,
            oauth_login,
            oauth_callback,
        ])
}