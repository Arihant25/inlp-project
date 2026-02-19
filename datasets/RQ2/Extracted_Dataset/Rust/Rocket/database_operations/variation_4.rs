/*
 * VARIATION 4: The "Pragmatic & Minimalist" Approach
 *
 * This implementation prioritizes conciseness and speed of development over strict
 * architectural layering. Database logic is often placed directly within or alongside
 * the route handlers that use it. It uses `anyhow::Result` for simplified, catch-all
 * error handling, reducing boilerplate.
 *
 * PROS: Fast to write, less code, easy to understand for small-scale projects.
 * CONS: Tightly couples HTTP layer with data access, becomes difficult to test and
 *       maintain as the application grows. Can lead to duplicated logic.
 */

// For setup instructions (Cargo.toml, .env, migrations), see Variation 1.
// You will also need to add `anyhow = "1.0"` to `Cargo.toml`.
// The database schema and migration SQL are identical.

#[macro_use]
extern crate rocket;
#[macro_use]
extern crate diesel;
#[macro_use]
extern crate diesel_migrations;

use anyhow::Context;
use rocket::fairing::{self, AdHoc};
use rocket::http::Status;
use rocket::response::status;
use rocket::serde::json::Json;
use rocket::{Build, Rocket};
use rocket_db_pools::{diesel::PgConnection, Connection, Database};
use uuid::Uuid;

// Main entry point
#[launch]
fn rocket() -> _ {
    dotenvy::dotenv().ok();
    rocket::build()
        .attach(DbConn::init())
        .attach(AdHoc::try_on_ignite("Diesel Migrations", run_migrations))
        .mount(
            "/",
            routes![
                create_user,
                get_user,
                assign_role,
                create_user_and_post_tx,
                get_posts,
            ],
        )
}

// Rocket's database pool fairing
#[derive(Database)]
#[database("postgres_db")]
pub struct DbConn(PgConnection);

// Diesel migrations runner (identical to Variation 1)
embed_migrations!("./migrations");
async fn run_migrations(rocket: Rocket<Build>) -> fairing::Result {
    // Assumes migrations are in a `./migrations` directory.
    // See Variation 1 for the required SQL.
    match DbConn::get_one(&rocket).await {
        Ok(conn) => {
            conn.run(|c| match embedded_migrations::run(c) {
                Ok(_) => Ok(rocket),
                Err(e) => {
                    error!("Failed to run Diesel migrations: {:?}", e);
                    Err(rocket)
                }
            })
            .await
        }
        Err(e) => {
            error!("Failed to get DB connection for migrations: {:?}", e);
            Err(rocket)
        }
    }
}

// ============== SCHEMA (identical to Variation 1) ==============
mod schema {
    diesel::table! {
        use diesel::sql_types::*;
        use super::models::PostStatusMapping;

        posts (id) {
            id -> Uuid,
            user_id -> Uuid,
            title -> Varchar,
            content -> Text,
            status -> PostStatusMapping,
        }
    }
    diesel::table! { roles (id) { id -> Uuid, name -> Varchar, } }
    diesel::table! { user_roles (user_id, role_id) { user_id -> Uuid, role_id -> Uuid, } }
    diesel::table! {
        users (id) {
            id -> Uuid,
            email -> Varchar,
            password_hash -> Varchar,
            is_active -> Bool,
            created_at -> Timestamptz,
        }
    }
    diesel::joinable!(posts -> users (user_id));
    diesel::joinable!(user_roles -> roles (role_id));
    diesel::joinable!(user_roles -> users (user_id));
    diesel::allow_tables_to_appear_in_same_query!(posts, roles, user_roles, users);
}

// ============== MODELS & DTOs (kept minimal) ==============
mod models {
    use super::schema::{posts, roles, user_roles, users};
    use chrono::{DateTime, Utc};
    use serde::{Deserialize, Serialize};
    use uuid::Uuid;
    use validator::Validate;

    #[derive(Debug, Clone, Copy, PartialEq, Eq, diesel_derive_enum::DbEnum, Serialize, Deserialize)]
    #[ExistingTypePath = "crate::schema::sql_types::PostStatusMapping"]
    pub enum PostStatus {
        DRAFT,
        PUBLISHED,
    }

    #[derive(Queryable, Selectable, Identifiable, Serialize, Debug)]
    #[diesel(table_name = users)]
    pub struct User {
        pub id: Uuid,
        pub email: String,
        #[serde(skip)]
        pub password_hash: String,
        pub is_active: bool,
        pub created_at: DateTime<Utc>,
    }

    #[derive(Queryable, Selectable, Identifiable, Associations, Serialize, Debug)]
    #[diesel(belongs_to(User))]
    #[diesel(table_name = posts)]
    pub struct Post {
        pub id: Uuid,
        pub user_id: Uuid,
        pub title: String,
        pub content: String,
        pub status: PostStatus,
    }

    #[derive(Queryable, Selectable, Identifiable, Serialize, Debug)]
    #[diesel(table_name = roles)]
    pub struct Role {
        pub id: Uuid,
        pub name: String,
    }

    #[derive(Deserialize, Validate)]
    pub struct CreateUserDto {
        #[validate(email)]
        pub email: String,
        #[validate(length(min = 8))]
        pub password: String,
    }

    #[derive(Deserialize)]
    pub struct AssignRoleDto {
        pub user_id: Uuid,
        pub role_name: String,
    }

    #[derive(Serialize)]
    pub struct UserDetails {
        pub user: User,
        pub posts: Vec<Post>,
        pub roles: Vec<Role>,
    }
}

// Custom error type for API responses using anyhow
struct ApiError(anyhow::Error);
type ApiResult<T> = Result<T, ApiError>;

impl<'r> rocket::response::Responder<'r, 'static> for ApiError {
    fn respond_to(self, _: &'r rocket::Request<'_>) -> rocket::response::Result<'static> {
        // Log the full error for debugging
        eprintln!("API Error: {:?}", self.0);
        // Provide a generic error to the client
        let body = format!("Error: {}", self.0);
        rocket::Response::build()
            .status(Status::InternalServerError)
            .header(rocket::http::ContentType::Text)
            .sized_body(body.len(), std::io::Cursor::new(body))
            .ok()
    }
}

impl<E: Into<anyhow::Error>> From<E> for ApiError {
    fn from(err: E) -> Self {
        ApiError(err.into())
    }
}

// ============== ROUTES & DB LOGIC (co-located) ==============

#[post("/users", format = "json", data = "<dto>")]
async fn create_user(mut db: Connection<DbConn>, dto: Json<models::CreateUserDto>) -> ApiResult<Json<models::User>> {
    use schema::users;
    
    dto.validate()?;

    let hashed_password = bcrypt::hash(&dto.password, bcrypt::DEFAULT_COST)
        .context("Failed to hash password")?;

    #[derive(Insertable)]
    #[diesel(table_name = users)]
    struct NewUser<'a> { id: Uuid, email: &'a str, password_hash: &'a str }

    let new_user = NewUser {
        id: Uuid::new_v4(),
        email: &dto.email,
        password_hash: &hashed_password,
    };

    let user = db.run(move |conn| {
        diesel::insert_into(users::table)
            .values(&new_user)
            .get_result(conn)
    }).await?;

    Ok(Json(user))
}

#[get("/users/<id>")]
async fn get_user(mut db: Connection<DbConn>, id: Uuid) -> ApiResult<Json<models::UserDetails>> {
    use schema::{posts, roles, user_roles, users};
    use diesel::prelude::*;

    let user = db.run(move |conn| users::table.find(id).first(conn)).await?;
    
    let posts = db.run(move |conn| {
        posts::table.filter(posts::user_id.eq(id)).load(conn)
    }).await?;

    let roles = db.run(move |conn| {
        user_roles::table
            .filter(user_roles::user_id.eq(id))
            .inner_join(roles::table)
            .select(models::Role::as_select())
            .load(conn)
    }).await?;

    Ok(Json(models::UserDetails { user, posts, roles }))
}

#[post("/users/roles", format = "json", data = "<dto>")]
async fn assign_role(mut db: Connection<DbConn>, dto: Json<models::AssignRoleDto>) -> ApiResult<status::NoContent> {
    use schema::{roles, user_roles};
    use diesel::prelude::*;

    let role_id = db.run(move |conn| {
        roles::table
            .filter(roles::name.eq(&dto.role_name))
            .select(roles::id)
            .first::<Uuid>(conn)
    }).await?;

    #[derive(Insertable)]
    #[diesel(table_name = user_roles)]
    struct NewUserRole { user_id: Uuid, role_id: Uuid }
    
    let new_link = NewUserRole { user_id: dto.user_id, role_id };

    db.run(move |conn| {
        diesel::insert_into(user_roles::table)
            .values(&new_link)
            .on_conflict_do_nothing()
            .execute(conn)
    }).await?;

    Ok(status::NoContent)
}

#[post("/users/transactional", format = "json", data = "<dto>")]
async fn create_user_and_post_tx(mut db: Connection<DbConn>, dto: Json<models::CreateUserDto>) -> ApiResult<status::Created<String>> {
    use schema::{posts, users};
    use diesel::prelude::*;
    
    dto.validate()?;

    let hashed_password = bcrypt::hash(&dto.password, bcrypt::DEFAULT_COST)?;
    let user_id = Uuid::new_v4();
    let email = dto.email.clone();

    db.run(move |conn| {
        conn.transaction(|tx_conn| {
            #[derive(Insertable)]
            #[diesel(table_name = users)]
            struct NewUser<'a> { id: Uuid, email: &'a str, password_hash: &'a str }

            let new_user = NewUser { id: user_id, email: &email, password_hash: &hashed_password };
            diesel::insert_into(users::table).values(&new_user).execute(tx_conn)?;

            #[derive(Insertable)]
            #[diesel(table_name = posts)]
            struct NewPost<'a> { id: Uuid, user_id: Uuid, title: &'a str, content: &'a str }

            let new_post = NewPost {
                id: Uuid::new_v4(),
                user_id,
                title: "First Post",
                content: "Hello from a transaction!",
            };
            diesel::insert_into(posts::table).values(&new_post).execute(tx_conn)?;

            Ok(())
        })
    }).await?;

    Ok(status::Created::new(format!("/users/{}", user_id)))
}

#[get("/posts?<status>")]
async fn get_posts(mut db: Connection<DbConn>, status: Option<models::PostStatus>) -> ApiResult<Json<Vec<models::Post>>> {
    use schema::posts::dsl::*;
    use diesel::prelude::*;

    let posts_vec = db.run(move |conn| {
        let mut query = posts.into_boxed();
        if let Some(s) = status {
            query = query.filter(super::schema::posts::status.eq(s));
        }
        query.load::<models::Post>(conn)
    }).await?;

    Ok(Json(posts_vec))
}