/*
 * VARIATION 3: The "Functional & Composable" Approach
 *
 * This implementation favors free-standing functions organized into modules over methods
 * on objects. The `db` module contains all database logic, subdivided by entity.
 * Models are pure data containers. This style emphasizes a clear data flow and can
 * make complex query composition more explicit.
 *
 * PROS: Highly composable, testable pure functions, aligns well with Rust's functional features.
 * CONS: Can lead to many functions and modules; less intuitive for those accustomed to OOP.
 */

// For setup instructions (Cargo.toml, .env, migrations), see Variation 1.
// The database schema and migration SQL are identical.

#[macro_use]
extern crate rocket;
#[macro_use]
extern crate diesel;
#[macro_use]
extern crate diesel_migrations;

use rocket::fairing::{self, AdHoc};
use rocket::http::Status;
use rocket::response::status;
use rocket::serde::json::Json;
use rocket::{Build, Rocket};
use rocket_db_pools::{diesel::PgConnection, Connection, Database};

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
                routes::create_user_endpoint,
                routes::get_user_endpoint,
                routes::assign_role_endpoint,
                routes::create_user_with_post_endpoint,
                routes::list_posts_endpoint,
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

// ============== MODELS (Plain Data Structs) ==============
mod models {
    use super::schema::{posts, roles, user_roles, users};
    use chrono::{DateTime, Utc};
    use serde::{Deserialize, Serialize};
    use uuid::Uuid;
    use validator::Validate;

    // --- Enums ---
    #[derive(Debug, Clone, Copy, PartialEq, Eq, diesel_derive_enum::DbEnum, Serialize, Deserialize)]
    #[ExistingTypePath = "crate::schema::sql_types::PostStatusMapping"]
    pub enum PostStatus {
        DRAFT,
        PUBLISHED,
    }

    // --- Entities ---
    #[derive(Queryable, Selectable, Identifiable, Serialize, Debug, Clone)]
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

    // --- DTOs ---
    #[derive(Deserialize, Validate)]
    pub struct UserCreateRequest {
        #[validate(email)]
        pub email: String,
        #[validate(length(min = 8))]
        pub password: String,
    }

    #[derive(Deserialize)]
    pub struct RoleAssignRequest {
        pub user_id: Uuid,
        pub role_name: String,
    }

    // --- API Response Structs ---
    #[derive(Serialize)]
    pub struct UserDetails {
        pub user: User,
        pub posts: Vec<Post>,
        pub roles: Vec<Role>,
    }
}

// ============== DATABASE LOGIC (Functional Style) ==============
mod db {
    use super::models::{Post, PostStatus, Role, User};
    use super::schema;
    use super::DbConn;
    use diesel::prelude::*;
    use rocket_db_pools::Connection;
    use thiserror::Error;
    use uuid::Uuid;

    #[derive(Error, Debug)]
    pub enum DbError {
        #[error("Database query failed: {0}")]
        Diesel(#[from] diesel::result::Error),
        #[error("Entity not found")]
        NotFound,
    }
    pub type DbResult<T> = Result<T, DbError>;

    // --- Generic Transaction Helper ---
    pub async fn transactionally<F, T>(db: &mut Connection<DbConn>, f: F) -> DbResult<T>
    where
        F: FnOnce(&mut PgConnection) -> Result<T, diesel::result::Error> + Send + 'static,
        T: Send + 'static,
    {
        db.run(move |conn| conn.transaction(f))
            .await
            .map_err(DbError::Diesel)
    }

    // --- User Functions ---
    pub mod users {
        use super::*;
        use schema::users;

        #[derive(Insertable)]
        #[diesel(table_name = users)]
        pub struct NewUser<'a> {
            pub id: Uuid,
            pub email: &'a str,
            pub password_hash: &'a str,
        }

        pub async fn create(db: &mut Connection<DbConn>, new_user: NewUser<'_>) -> DbResult<User> {
            db.run(move |conn| {
                diesel::insert_into(users::table)
                    .values(&new_user)
                    .get_result(conn)
            })
            .await
            .map_err(DbError::Diesel)
        }

        pub async fn find(db: &mut Connection<DbConn>, id: Uuid) -> DbResult<User> {
            db.run(move |conn| users::table.find(id).first(conn))
                .await
                .map_err(|e| match e {
                    diesel::result::Error::NotFound => DbError::NotFound,
                    _ => DbError::Diesel(e),
                })
        }
    }

    // --- Post Functions ---
    pub mod posts {
        use super::*;
        use schema::posts;

        #[derive(Insertable)]
        #[diesel(table_name = posts)]
        pub struct NewPost<'a> {
            pub id: Uuid,
            pub user_id: Uuid,
            pub title: &'a str,
            pub content: &'a str,
        }

        pub async fn create(db: &mut Connection<DbConn>, new_post: NewPost<'_>) -> DbResult<Post> {
            db.run(move |conn| {
                diesel::insert_into(posts::table)
                    .values(&new_post)
                    .get_result(conn)
            })
            .await
            .map_err(DbError::Diesel)
        }

        pub async fn find_by_user_id(db: &mut Connection<DbConn>, user_id: Uuid) -> DbResult<Vec<Post>> {
            db.run(move |conn| {
                posts::table
                    .filter(posts::user_id.eq(user_id))
                    .load(conn)
            })
            .await
            .map_err(DbError::Diesel)
        }

        pub async fn find_filtered(db: &mut Connection<DbConn>, status_filter: Option<PostStatus>) -> DbResult<Vec<Post>> {
            db.run(move |conn| {
                let mut query = posts::table.into_boxed();
                if let Some(status) = status_filter {
                    query = query.filter(posts::status.eq(status));
                }
                query.load(conn)
            })
            .await
            .map_err(DbError::Diesel)
        }
    }

    // --- Role Functions ---
    pub mod roles {
        use super::*;
        use schema::{roles, user_roles};

        pub async fn find_by_name(db: &mut Connection<DbConn>, name: &str) -> DbResult<Role> {
            let name_owned = name.to_string();
            db.run(move |conn| roles::table.filter(roles::name.eq(name_owned)).first(conn))
                .await
                .map_err(|e| match e {
                    diesel::result::Error::NotFound => DbError::NotFound,
                    _ => DbError::Diesel(e),
                })
        }

        pub async fn find_by_user_id(db: &mut Connection<DbConn>, user_id: Uuid) -> DbResult<Vec<Role>> {
            db.run(move |conn| {
                user_roles::table
                    .filter(user_roles::user_id.eq(user_id))
                    .inner_join(roles::table)
                    .select(Role::as_select())
                    .load(conn)
            })
            .await
            .map_err(DbError::Diesel)
        }

        pub async fn assign_to_user(db: &mut Connection<DbConn>, user_id: Uuid, role_id: Uuid) -> DbResult<()> {
            #[derive(Insertable)]
            #[diesel(table_name = user_roles)]
            struct NewUserRole { user_id: Uuid, role_id: Uuid }

            let new_link = NewUserRole { user_id, role_id };
            db.run(move |conn| {
                diesel::insert_into(user_roles::table)
                    .values(&new_link)
                    .on_conflict_do_nothing()
                    .execute(conn)
            })
            .await?;
            Ok(())
        }
    }
}

// ============== ROUTES ==============
mod routes {
    use super::db::{self, DbError};
    use super::models::{RoleAssignRequest, User, UserCreateRequest, UserDetails, PostStatus};
    use super::DbConn;
    use rocket::http::Status;
    use rocket::response::status;
    use rocket::serde::json::Json;
    use rocket_db_pools::Connection;
    use uuid::Uuid;
    use validator::Validate;

    type ApiError = status::Custom<String>;
    type ApiResponse<T> = Result<T, ApiError>;

    fn map_db_error(err: DbError) -> ApiError {
        let status = match err {
            DbError::NotFound => Status::NotFound,
            _ => Status::InternalServerError,
        };
        status::Custom(status, err.to_string())
    }

    #[post("/users", format = "json", data = "<request>")]
    pub async fn create_user_endpoint(
        mut db: Connection<DbConn>,
        request: Json<UserCreateRequest>,
    ) -> ApiResponse<Json<User>> {
        request.validate().map_err(|e| status::Custom(Status::BadRequest, e.to_string()))?;

        let hashed_password = bcrypt::hash(&request.password, bcrypt::DEFAULT_COST)
            .map_err(|_| status::Custom(Status::InternalServerError, "Hashing failed".into()))?;

        let new_user = db::users::NewUser {
            id: Uuid::new_v4(),
            email: &request.email,
            password_hash: &hashed_password,
        };

        db::users::create(&mut db, new_user)
            .await
            .map(Json)
            .map_err(map_db_error)
    }

    #[get("/users/<id>")]
    pub async fn get_user_endpoint(mut db: Connection<DbConn>, id: Uuid) -> ApiResponse<Json<UserDetails>> {
        let user = db::users::find(&mut db, id).await.map_err(map_db_error)?;
        let posts = db::posts::find_by_user_id(&mut db, id).await.map_err(map_db_error)?;
        let roles = db::roles::find_by_user_id(&mut db, id).await.map_err(map_db_error)?;

        Ok(Json(UserDetails { user, posts, roles }))
    }

    #[post("/users/roles", format = "json", data = "<request>")]
    pub async fn assign_role_endpoint(
        mut db: Connection<DbConn>,
        request: Json<RoleAssignRequest>,
    ) -> ApiResponse<status::NoContent> {
        let role = db::roles::find_by_name(&mut db, &request.role_name).await.map_err(map_db_error)?;
        db::roles::assign_to_user(&mut db, request.user_id, role.id).await.map_err(map_db_error)?;
        Ok(status::NoContent)
    }

    #[post("/users/transactional", format = "json", data = "<request>")]
    pub async fn create_user_with_post_endpoint(
        mut db: Connection<DbConn>,
        request: Json<UserCreateRequest>,
    ) -> ApiResponse<status::Created<String>> {
        request.validate().map_err(|e| status::Custom(Status::BadRequest, e.to_string()))?;

        let hashed_password = bcrypt::hash(&request.password, bcrypt::DEFAULT_COST)
            .map_err(|_| status::Custom(Status::InternalServerError, "Hashing failed".into()))?;

        let user_id = Uuid::new_v4();
        let email = request.email.clone();

        db::transactionally(&mut db, move |conn| {
            let new_user = db::users::NewUser {
                id: user_id,
                email: &email,
                password_hash: &hashed_password,
            };
            let user: User = diesel::insert_into(db::schema::users::table)
                .values(&new_user)
                .get_result(conn)?;

            let new_post = db::posts::NewPost {
                id: Uuid::new_v4(),
                user_id: user.id,
                title: "My First Post",
                content: "Created transactionally!",
            };
            diesel::insert_into(db::schema::posts::table)
                .values(&new_post)
                .execute(conn)?;

            Ok(())
        })
        .await
        .map_err(map_db_error)?;

        Ok(status::Created::new(format!("/users/{}", user_id)))
    }

    #[get("/posts?<status>")]
    pub async fn list_posts_endpoint(
        mut db: Connection<DbConn>,
        status: Option<PostStatus>,
    ) -> ApiResponse<Json<Vec<super::models::Post>>> {
        db::posts::find_filtered(&mut db, status)
            .await
            .map(Json)
            .map_err(map_db_error)
    }
}