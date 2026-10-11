# TripGuard

TripGuard is a UAE tourist-visa **document-readiness** platform for Indian-passport
travellers and the agencies that assist them. It helps a traveller check that their
documents meet a visa route's requirements *before* submitting an application, and it
gives agency staff a shared dashboard for inquiries, appointment slots and bookings.

The project is three cooperating parts that share one backend:

- **Android app** (`tripguard-app/`) — offline-first traveller experience: pick a visa
  route, capture documents, run on-device OCR, review extracted fields, and get a
  readiness report with concrete correction steps.
- **Agency website** (`tripguard-website/`) — React + TypeScript dashboard where agency
  staff sign in, triage inquiries, publish appointment slots, and manage bookings.
- **Agency API** (`agency_api/`) — FastAPI service that backs both clients (contact
  inquiries, appointment slots, concurrency-safe bookings, staff auth).

## Privacy boundary

The API is deliberately narrow about personal data and is the design contract for the
whole system:

- No endpoint accepts or stores **visa documents, raw OCR text, or OCR-derived personal
  fields**.
- Only explicitly **consented** contact details, visa route, an optional preparation
  summary (counts like "2 missing documents, 1 issue"), and a consent record are persisted.
- Documents and OCR stay on the device in the Android app.

## Repository layout

```
TripGuard/
├── agency_api/           FastAPI backend (Python, SQLAlchemy, SQLite)
│   ├── main.py           App, routes, auth, rate limiting
│   ├── db.py             Models + engine/session setup (WAL, atomic bookings)
│   ├── schemas.py        Pydantic request/response models
│   ├── security.py       Password hashing, JWT, rate limiter
│   ├── booking_validation.py  Slot/booking state transitions
│   ├── seed_dev.py       CLI: seed synthetic demo data
│   └── tests/            pytest suite
├── tripguard-app/        Android app (Kotlin, Jetpack Compose, ML Kit OCR)
│   ├── app/              App module (screens, domain, data layers)
│   └── docs/             BACKEND_API_CONTRACT.md
├── tripguard-website/    Agency dashboard (React, TypeScript, Vite)
│   └── src/              Pages, components, services, state
├── data/                 SQLite dev database (git-ignored)
└── pytest.ini
```

## Prerequisites

- **Python** 3.11+ (developed against 3.14) for the API
- **Node.js** 18+ and npm for the website
- **Android Studio** (with Android SDK 37) or the Android SDK + JDK 17 for the app

## Getting started

### 1. Agency API

```bash
python -m venv .venv
source .venv/bin/activate
pip install fastapi "uvicorn[standard]" sqlalchemy pydantic bcrypt pyjwt pytest httpx

# (optional) seed synthetic demo data: staff login, inquiries, slots, bookings
python -m agency_api.seed_dev

# run the dev server on http://localhost:8000
uvicorn agency_api.main:app --reload --port 8000
```

Interactive API docs are available at `http://localhost:8000/docs`.

Run the tests from the repository root:

```bash
pytest
```

> The dev seed creates staff login `agency_admin` / `tripguard-demo-password` and
> clearly-labelled synthetic records. Never run it against a production database.

### 2. Agency website

```bash
cd tripguard-website
npm install
npm run dev        # http://localhost:5173
```

The dashboard talks to the API at `http://localhost:8000` by default. Override it with
`VITE_AGENCY_API_BASE` (or a `.env` file) when the API runs elsewhere.

Other scripts:

```bash
npm run typecheck  # tsc for app + node configs
npm run lint       # eslint
npm run test       # vitest
npm run build      # typecheck + production build into dist/
```

### 3. Android app

Open `tripguard-app/` in Android Studio, or build from the command line:

```bash
cd tripguard-app
./gradlew assembleDebug          # debug APK
./gradlew test                   # unit tests
```

The app is offline-first: it always works from the bundled dataset in
`app/src/main/assets/` plus on-device state, and only calls the API when a server is
reachable, degrading gracefully otherwise.

The backend base URL is centralized in `BuildConfig.API_BASE_URL` (default
`http://10.0.2.2:8000/`, the emulator host loopback) and can be overridden at build time
or at runtime from **Settings → Backend address**:

```bash
./gradlew assembleDebug -PapiBaseUrl="http://192.168.1.20:8000/"
```

## Configuration

| Component | Variable | Default | Notes |
| --- | --- | --- | --- |
| API | `DATABASE_PATH` | `data/tripguard.db` | SQLite file location |
| API | `JWT_SECRET` | dev-only default | **Set a real secret in production** |
| Website | `VITE_AGENCY_API_BASE` | `http://localhost:8000` | Agency API base URL |
| App | `apiBaseUrl` (Gradle property) | `http://10.0.2.2:8000/` | Also overridable at runtime |

CORS on the API allows the Vite dev server origins (`localhost:5173` / `127.0.0.1:5173`)
only.

## API surface

The API serves both clients under `/api`:

- `GET /health` — liveness probe
- `POST /api/agency/login` — staff auth (JWT)
- Public traveller endpoints — create/list an inquiry by token, list slots, create and
  cancel a booking
- Agency endpoints — inquiries, notes and status, slots, bookings, dashboard summary

Full request/response shapes for the traveller app contract live in
`tripguard-app/docs/BACKEND_API_CONTRACT.md`.

## License

Not yet licensed. All rights reserved until a license is added.
