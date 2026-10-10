# iVisit — Visitor Management System

iVisit is a three-component visitor management system for institutional facilities. It records visitor check-ins and check-outs, scans government-issued IDs via OCR, and tracks visitor passes via RFID readers. Security guards use the React frontend to manage visitor logs; the local desktop helper handles all hardware interaction.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Prerequisites](#prerequisites)
3. [Database Setup](#database-setup)
4. [Environment Setup](#environment-setup)
5. [Running in Development](#running-in-development)
6. [Two-Factor Authentication (2FA)](#two-factor-authentication-2fa)
7. [RFID Setup](#rfid-setup)
8. [OCR Setup](#ocr-setup)
9. [Production Build](#production-build)
10. [Ports](#ports)
11. [Environment Variables Reference](#environment-variables-reference)

---

## Architecture

```
iVisit_1_FINAL/
├── backend/          # Spring Boot 2.7 REST API — handles all business logic,
│                     #   user accounts, visitor logs, passes, and email verification.
│                     #   Connects to MySQL 8. Listens on port 8080.
│
├── ivisit-helper/    # Spring Boot 2.7 desktop helper — runs locally on the guard's
│                     #   workstation. Interfaces with the Tesseract OCR engine,
│                     #   the OCR.space cloud API, and an attached RFID reader.
│                     #   The frontend calls this service directly for OCR/RFID.
│                     #   Listens on port 8765. OPTIONAL — the app works without it,
│                     #   but OCR and RFID will not function.
│
├── i-visit-master/   # React 19 + Vite 7 frontend — the main guard interface.
│                     #   Calls the backend at port 8080 and the helper at port 8765.
│                     #   Served on port 5173 in development.
│
└── TesseractOCR5_Install/   # Bundled Tesseract OCR 5 installer for Windows.
```

### How the components connect

```
Browser (port 5173)
      │
      ├──── REST calls ────────────────────► Backend API (port 8080)
      │                                            │
      │                                            └──── JDBC ────► MySQL 8
      │
      └──── REST calls ────────────────────► ivisit-helper (port 8765)
                                                   │
                                                   ├── Tesseract OCR (local binary)
                                                   ├── OCR.space API (cloud, optional)
                                                   └── RFID reader (USB/serial, optional)
```

---

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Java (JDK) | 17+ | Both `backend` and `ivisit-helper` require JDK 17 |
| Node.js | 18+ | Required for `i-visit-master` |
| npm | 9+ | Bundled with Node 18 |
| MySQL | 8+ | The only supported database |
| Tesseract OCR | 5 | Windows installer provided in `TesseractOCR5_Install/` |
| RFID reader | — | Optional USB/serial reader; helper gracefully skips if absent |
| OCR.space API key | — | Optional; provides better accuracy than local Tesseract |

### Installing Tesseract OCR (Windows)

1. Run the installer from `TesseractOCR5_Install/` (or download from [UB Mannheim builds](https://github.com/UB-Mannheim/tesseract/wiki)).
2. Accept the default installation path: `C:\Program Files\Tesseract-OCR\`.
3. The helper expects the tessdata directory at `C:\Program Files\Tesseract-OCR\tessdata` by default. Override this with the `TESSERACT_DATAPATH` environment variable if you install elsewhere.

---

## Database Setup

Connect to your MySQL 8 instance and create the database:

```sql
CREATE DATABASE ivisitdb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

The backend will create all tables automatically via Hibernate (`JPA_DDL_AUTO=update`) on first startup. No migration scripts need to be run manually.

---

## Environment Setup

Each sub-project has its own `.env.example`. Copy each one before starting the services.

### Backend

```bash
cp backend/.env.example backend/.env
```

Open `backend/.env` and fill in at minimum:

```properties
DB_USERNAME=root
DB_PASSWORD=your_mysql_password
MAIL_USERNAME=your_gmail_address@gmail.com
MAIL_PASSWORD=your_gmail_app_password
MAIL_FROM=your_gmail_address@gmail.com
APP_FRONTEND_BASE_URL=http://localhost:5173
APP_CORS_ALLOWED_ORIGINS=http://localhost:5173
```

> **Gmail app password:** In your Google account, go to Security → 2-Step Verification → App passwords. Generate a password for "Mail".

### ivisit-helper

```bash
cp ivisit-helper/.env.example ivisit-helper/.env
```

For local OCR, the defaults work if Tesseract is installed at the standard Windows path. For OCR.space, fill in:

```properties
OCRSPACE_API_KEY=your_ocrspace_api_key
```

### Frontend

```bash
cp i-visit-master/.env.example i-visit-master/.env.local
```

The default values point to `localhost:8080` (backend) and `localhost:8765` (helper) and require no changes for local development.

---

## Running in Development

Start each service in a separate terminal, in the order shown.

### 1. Backend

```bash
cd backend
./mvnw spring-boot:run
```

On first startup with `SPRING_PROFILES_ACTIVE=dev` (the default when no `.env` sets it to `prod`), the backend seeds the database with five user accounts, two stations, and sample visitor data. **All seeded users have email verification pre-confirmed**, so you can log in immediately without going through the email verification flow.

**Default dev credentials:**

| Email | Password | Username | Role |
|---|---|---|---|
| `admin@email.com` | `password` | `admin` | Admin |
| `support@email.com` | `password` | `support` | Support |
| `guard_gate1@email.com` | `password` | `guard_gate1` | Guard (Gate 1) |
| `guard_gate2@email.com` | `password` | `guard_gate2` | Guard (Gate 2) |
| `guard_lp@email.com` | `password` | `guard_lobby_parking` | Guard (Lobby/Parking) |

Expected startup output (dev):

```
Started BackendApplication in X.XXX seconds
```

### 2. Frontend

```bash
cd i-visit-master
npm install
npm run dev
```

Open [http://localhost:5173](http://localhost:5173) in your browser.

### 3. Helper (Optional)

The helper is required only if you want OCR or RFID functionality. The frontend will show OCR and RFID features regardless, but they will fail with a network error if the helper is not running.

```bash
cd ivisit-helper
./mvnw spring-boot:run
```

If no RFID reader is connected, the helper logs a warning on startup and continues running; OCR still works.

---

## Two-Factor Authentication (2FA)

iVisit enforces TOTP-based 2FA for all accounts.

**First login flow:**

1. Submit your email and password on the login page.
2. The backend responds with `twoFactorSetupRequired: true` and a `qrCodeUrl`.
3. The frontend displays a QR code. Scan it with any TOTP authenticator app (Google Authenticator, Authy, Microsoft Authenticator, etc.).
4. Enter the 6-digit code from the app to complete setup.
5. On all subsequent logins, after entering your password you will be prompted for the current 6-digit code.

> **Dev shortcut:** Seeded accounts do not have 2FA pre-configured. The first time you log in with any seeded account, you will go through the QR code setup flow above.

---

## RFID Setup

### Hardware requirement

Connect a USB or serial RFID reader to the workstation running `ivisit-helper` **before** starting the helper. The helper's `RfidLoopRunner` scans for a reader on startup. If none is detected, it logs a warning and skips RFID polling; OCR continues to work normally.

### Station binding

The helper binds itself to a specific station via `ivisit-helper/station-config.properties`. This file is created or updated from the frontend when a guard selects a station. The relevant environment variable is:

```properties
HELPER_STATION_ID=1                             # default station if config file is absent
HELPER_STATION_CONFIG_PATH=station-config.properties
```

### Frontend RFID flow

1. From the check-in page, click **Read Card**.
2. The frontend polls `GET http://localhost:8765/api/read-card-uid`.
3. Tap the visitor's RFID card on the reader.
4. The helper returns the card UID; the frontend fetches the associated visitor pass from the backend.

---

## OCR Setup

iVisit supports two OCR modes, both routed through the helper:

| Mode | Requirement | Notes |
|---|---|---|
| Local Tesseract | Tesseract 5 installed | Runs offline; accuracy depends on card quality |
| OCR.space (cloud) | `OCRSPACE_API_KEY` | Better accuracy; requires internet access |

The helper automatically uses OCR.space if `OCRSPACE_API_KEY` is set; otherwise it falls back to local Tesseract.

### Tesseract data path

The default Windows path is `C:\Program Files\Tesseract-OCR\tessdata`. If you installed Tesseract to a different location, set the environment variable in `ivisit-helper/.env`:

```properties
TESSERACT_DATAPATH=C:\\Your\\Custom\\Path\\tessdata
```

### OCR.space API key

Get a free key at [https://ocr.space/](https://ocr.space/) (500 requests/day on the free tier). Set it in `ivisit-helper/.env`:

```properties
OCRSPACE_API_KEY=your_key_here
```

### OCR debug mode

Set `VITE_OCR_DEBUG_MODE=true` in `i-visit-master/.env.local` to enable the OCR Test Page, which lets you upload an image and inspect what the helper returns.

---

## Production Build

### Backend

```bash
cd backend
./mvnw clean package -DskipTests
java -jar target/*.jar --spring.profiles.active=prod
```

Set all required environment variables (see [Environment Variables Reference](#environment-variables-reference)) before starting. Key production settings:

```properties
BACKEND_PROFILE=prod
DB_PASSWORD=strong_password_here
JPA_DDL_AUTO=validate        # do not let Hibernate mutate the schema in production
JPA_SHOW_SQL=false
H2_CONSOLE_ENABLED=false
ERROR_INCLUDE_MESSAGE=never
ERROR_INCLUDE_STACKTRACE=never
ADMIN_EMAIL=admin@yourdomain.com
ADMIN_PASSWORD=strong_admin_password
```

### Frontend

```bash
cd i-visit-master
npm run build
```

The built files are in `i-visit-master/dist/`. Deploy them to any static hosting service (Nginx, Apache, Netlify, etc.). Set `VITE_API_BASE_URL` and `VITE_HELPER_BASE_URL` to your production URLs in `.env.local` (or as build-time environment variables) before running `npm run build`.

### Helper

The helper is a **local desktop app** and is not deployed to a server. Run it on each guard workstation the same way as in development.

---

## Ports

| Service | Default Port | Config Variable |
|---|---|---|
| Backend API | `8080` | `BACKEND_SERVER_PORT` |
| ivisit-helper | `8765` | `HELPER_SERVER_PORT` |
| Frontend (dev) | `5173` | Vite default |

---

## Environment Variables Reference

### `backend/.env`

| Variable | Required | Default | Notes |
|---|---|---|---|
| `BACKEND_PROFILE` | no | `dev` | Set to `prod` for production |
| `DB_HOST` | no | `127.0.0.1` | MySQL host |
| `DB_PORT` | no | `3306` | MySQL port |
| `DB_NAME` | no | `ivisitdb` | Database name |
| `DB_USERNAME` | **yes** | `root` | MySQL username |
| `DB_PASSWORD` | **yes** | *(empty)* | MySQL password |
| `DB_USE_SSL` | no | `false` | Set `true` in production |
| `DB_TIMEZONE` | no | `UTC` | |
| `APP_FRONTEND_BASE_URL` | **yes** | `http://localhost:5173` | Used in email verification links |
| `APP_CORS_ALLOWED_ORIGINS` | **yes** | `http://localhost:5173` | Comma-separated list |
| `BACKEND_SERVER_PORT` | no | `8080` | HTTP port the API listens on |
| `JPA_DDL_AUTO` | no | `update` | Use `validate` in production |
| `JPA_SHOW_SQL` | no | `true` | Set `false` in production |
| `JPA_FORMAT_SQL` | no | `false` | |
| `FILE_UPLOAD_DIR` | no | `uploads/` | Relative path for uploaded files |
| `H2_CONSOLE_ENABLED` | no | `true` | Disable in production |
| `H2_CONSOLE_PATH` | no | `/h2-console` | |
| `MAIL_HOST` | email | `smtp.gmail.com` | SMTP server |
| `MAIL_PORT` | email | `587` | SMTP port |
| `MAIL_USERNAME` | email | *(empty)* | SMTP username |
| `MAIL_PASSWORD` | email | *(empty)* | SMTP password or app password |
| `MAIL_FROM` | email | *(empty)* | Sender address in outgoing emails |
| `MAIL_SMTP_AUTH` | no | `true` | |
| `MAIL_SMTP_STARTTLS` | no | `true` | |
| `ADMIN_EMAIL` | prod | *(empty)* | Production seeder admin email |
| `ADMIN_PASSWORD` | prod | *(empty)* | Production seeder admin password |
| `ADMIN_USERNAME` | no | `Admin` | Production seeder admin display name |
| `APP_2FA_ISSUER` | no | `iVisitUST` | Label shown in authenticator apps |
| `ERROR_INCLUDE_MESSAGE` | no | `never` | Restrict in production |
| `ERROR_INCLUDE_BINDING` | no | `never` | |
| `ERROR_INCLUDE_STACKTRACE` | no | `never` | |
| `LOG_LEVEL` | no | `WARN` | |
| `LOG_FILE_PATH` | no | `logs` | |

### `ivisit-helper/.env`

| Variable | Required | Default | Notes |
|---|---|---|---|
| `HELPER_SERVER_PORT` | no | `8765` | HTTP port the helper listens on |
| `HELPER_CORS_ALLOWED_ORIGINS` | no | `http://localhost:5173` | Comma-separated origins |
| `TESSERACT_DATAPATH` | **yes** | `C:\Program Files\Tesseract-OCR\tessdata` | Path to tessdata directory |
| `HELPER_BACKEND_URL` | no | `http://localhost:8080` | Must match the backend URL |
| `HELPER_STATION_ID` | no | `1` | Overridden by `station-config.properties` when set via frontend |
| `HELPER_STATION_CONFIG_PATH` | no | `station-config.properties` | |
| `HELPER_POLL_DELAY_MS` | no | `10000` | RFID polling interval in milliseconds |
| `OCRSPACE_API_KEY` | OCR | *(empty)* | Free key from [https://ocr.space/](https://ocr.space/) |
| `OCRSPACE_API_URL` | no | `https://api.ocr.space/parse/image` | |
| `LOG_LEVEL` | no | `INFO` | |
| `MULTIPART_MAX_FILE_SIZE` | no | `50MB` | Max size per uploaded image |
| `MULTIPART_MAX_REQUEST_SIZE` | no | `100MB` | |

### `i-visit-master/.env.local`

| Variable | Required | Default | Notes |
|---|---|---|---|
| `VITE_API_BASE_URL` | **yes** | `http://localhost:8080` | Backend API URL |
| `VITE_HELPER_BASE_URL` | **yes** | `http://localhost:8765` | Helper URL (OCR/RFID) |
| `VITE_OCR_DEBUG_MODE` | no | `true` | Enables OCR Test Page; set `false` in production |
| `VITE_APP_ENV` | no | `development` | |
