# TripGuard Backend API Contract

Status: **design contract**. No backend implementation ships in this repository. The Android
app is offline-first: it always works from the bundled dataset and on-device state, and talks to
this API only when a server is reachable. Every call degrades gracefully to local data.

Base URL is centralised at build time via `BuildConfig.API_BASE_URL` and can be overridden at
runtime from **Settings → Backend address**.

```
Default (emulator host loopback): http://10.0.2.2:8000/
Build override:                   ./gradlew assembleDebug -PapiBaseUrl="http://192.168.1.20:8000/"
```

All request and response bodies are JSON (`application/json; charset=utf-8`) except the document
upload, which is `multipart/form-data`. Unknown response keys are ignored by the client.
Timestamps are epoch milliseconds (UTC). Currency amounts are decimal.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/health` | Liveness probe used by the Settings screen. |
| GET | `/api/v1/dataset` | Versioned visa-requirements dataset. |
| GET | `/api/v1/config/document-policy` | Upload acceptance policy (may override app defaults). |
| POST | `/api/v1/applications` | Create an application. |
| GET | `/api/v1/applications/{id}` | Fetch an application with documents, timeline, comments, handoff and appointment. |
| POST | `/api/v1/applications/{id}/documents/upload` | Upload/replace a document (multipart). |
| POST | `/api/v1/applications/{id}/documents/{documentId}/manual-review` | Request a human review. |
| POST | `/api/v1/applications/{id}/documents/share-consent` | Record explicit document-sharing consent. |
| POST | `/api/v1/applications/{id}/appointments/{appointmentId}/respond` | Accept, change or decline an appointment proposal. |

The client treats `404` as `NOT_FOUND` and any other non-2xx as `SERVER`. Network failures,
timeouts, DNS failures and parse errors map to distinct offline/error states; the UI keeps
showing local data in every failure case.

---

### `GET /health`

- Response `200 OK`, body may be empty; any 2xx counts as reachable.

---

### `GET /api/v1/dataset`

Returns the versioned dataset (schema `1.0.1` when served by the reference backend). The app
ships the identical file at
`app/src/main/assets/tripguard_uae_visa_requirements_indian_passports_v1.0.1.json` and prefers a
reachable backend copy so requirements can be updated without an app release.

Top-level shape (abridged — see `data/dataset/DatasetDto.kt` for the full typed model):

```json
{
  "dataset_id": "tripguard_uae_visa_requirements_indian_passports",
  "schema_version": "1.0.1",
  "destination_country": "United Arab Emirates",
  "destination_emirate": "Dubai",
  "supported_applicant_scope": {
    "nationality": "Indian",
    "passport_type": "Ordinary passport",
    "purpose": "Tourism",
    "unsupported_applicants": ["..."],
    "scope_enforcement": "..."
  },
  "research_metadata": { "last_verified_at": "2026-...", "research_status": "..." },
  "verification_policy": {
    "mode": "...",
    "automatic_checks_allowed": ["..."],
    "never_automatically_decide": ["..."],
    "low_confidence_action": "...",
    "official_decision_disclaimer": "..."
  },
  "visa_types": [
    {
      "id": "uae_tourist_30d_single",
      "official_name": "...",
      "display_name": "30-day single-entry tourist visa",
      "category": "tourism",
      "entry_type": "single",
      "permitted_stay": { "value": 30, "unit": "days", "conditions": [] },
      "documents": [
        {
          "id": "passport",
          "name": "Passport",
          "description": "...",
          "mandatory": true,
          "applicability": { "scope": "...", "conditions": [] },
          "specifications": { "minimum_validity_days": 180, "accepted_formats": ["pdf", "jpg"] },
          "verification_rules": [ { "id": "...", "rule_type": "...", "severity": "..." } ],
          "source_ids": ["..."]
        }
      ],
      "conditional_documents": [
        {
          "id": "...",
          "name": "...",
          "requirement_status": "not_applicable_to_current_indian_scope",
          "applicability": { "scope": "...", "conditions": [] }
        }
      ],
      "fees": [ { "amount": 0, "currency": "AED", "description": "..." } ]
    }
  ],
  "sources": [ { "id": "...", "title": "...", "organization": "...", "url": "...", "source_type": "..." } ],
  "limitations": ["..."]
}
```

---

### `GET /api/v1/config/document-policy`

Optional. When reachable it overrides the bundled `DocumentPolicy` defaults.

```json
{
  "acceptedExtensions": ["pdf", "jpg", "jpeg", "png"],
  "maxFileSizeBytes": 10485760,
  "source": "backend"
}
```

Client defaults when this endpoint is unavailable: extensions `pdf, jpg, jpeg, png`, max size
`10 MiB`, `source = "app_default"`.

---

### `POST /api/v1/applications`

Request:

```json
{
  "visaTypeId": "uae_tourist_30d_single",
  "applicant": {
    "fullName": "Ananya Subramanian",
    "nationality": "Indian",
    "passportType": "Ordinary passport",
    "purpose": "Tourism",
    "contactEmail": "optional@example.com",
    "contactPhone": "+91..."
  }
}
```

Response `201 Created`: a full application object (see below).

---

### `GET /api/v1/applications/{id}`

Response `200 OK`:

```json
{
  "id": "srv_8f2c...",
  "reference": "TG-8K3L2-471",
  "visaTypeId": "uae_tourist_30d_single",
  "status": "ACTION_REQUIRED",
  "createdAt": 1760000000000,
  "updatedAt": 1760003600000,
  "documents": [
    {
      "documentId": "passport",
      "state": "NEEDS_REVIEW",
      "extractedFields": [
        { "key": "full_name", "label": "Full name", "value": "ANANYA SUBRAMANIAN", "confidence": 0.93 },
        { "key": "expiry_date", "label": "Expiry date", "value": "2027-04-30", "confidence": 0.71 }
      ],
      "issueNote": "Expiry date is less than six months away.",
      "agencyNote": null,
      "remote_document_id": "doc_01H..."
    }
  ],
  "timeline": [
    {
      "id": "evt_01H...",
      "timestamp": 1760003600000,
      "title": "Passport needs review",
      "description": "Expiry below minimum validity.",
      "kind": "DOCUMENT_NEEDS_REVIEW"
    }
  ],
  "agencyComments": [
    {
      "id": "cmt_01H...",
      "documentId": "passport",
      "documentName": "Passport",
      "message": "Please re-upload a clearer copy of the passport bio page.",
      "requestedAt": 1760003600000,
      "resolved": false
    }
  ],
  "handoff": {
    "id": "hoff_01H...",
    "agencyName": "Desert Rose Travel LLC",
    "purpose": "Visa processing",
    "requestedDocumentIds": ["passport", "photo", "proof_of_return", "hotel_booking"],
    "status": "AWAITING_CONSENT",
    "note": null,
    "confirmedDocumentIds": []
  },
  "appointment": {
    "id": "apt_01H...",
    "agencyName": "Desert Rose Travel LLC",
    "purpose": "Original-document verification visit",
    "proposedStartAt": 1760364000000,
    "proposedEndAt": 1760367600000,
    "location": "Sheikh Zayed Road, Dubai",
    "alternatives": [
      { "id": "slot_01H...", "startAt": 1760428800000, "endAt": 1760432400000 }
    ],
    "status": "AWAITING_RESPONSE",
    "message": null
  }
}
```

`status` is one of: `DRAFT`, `IN_PROGRESS`, `ACTION_REQUIRED`, `UNDER_REVIEW`,
`READY_FOR_AGENCY`.

`state` is one of: `NOT_UPLOADED`, `UPLOADING`, `UPLOAD_FAILED`, `UPLOADED`, `PROCESSING`,
`EXTRACTED`, `NEEDS_REVIEW`, `POTENTIAL_ISSUE`, `ACCEPTED`.

`kind` is one of: `CREATED`, `DOCUMENT_UPLOADED`, `DOCUMENT_PROCESSED`, `DOCUMENT_NEEDS_REVIEW`,
`STATUS_CHANGED`, `AGENCY_COMMENT`, `CORRECTION_REQUESTED`, `APPLICANT_CORRECTION`,
`SHARE_CONSENT_REQUESTED`, `SHARE_CONSENT_CONFIRMED`, `APPOINTMENT_PROPOSED`,
`APPOINTMENT_RESPONDED`, `AGENCY_PROCESSING_STARTED`, `SYNCED`.

`handoff.status` is one of: `AWAITING_CONSENT`, `SUBMITTING`, `CONFIRMED`, `DECLINED`, `FAILED`.

`appointment.status` is one of: `AWAITING_RESPONSE`, `ACCEPTED`, `CHANGE_REQUESTED`, `DECLINED`,
`CONFIRMED`, `CANCELLED`.

`handoff` and `appointment` are nullable. The client only shows what the backend returns for these
fields; it never invents them locally.

---

### `POST /api/v1/applications/{id}/documents/upload`

`multipart/form-data`:

| Part | Type | Notes |
| --- | --- | --- |
| `documentId` | text | Checklist document id, e.g. `passport`. |
| `file` | file | Validated locally against the document policy before upload. |

Response `200 OK` with the updated document object:

```json
{
  "documentId": "passport",
  "state": "PROCESSING",
  "extractedFields": [],
  "issueNote": null,
  "agencyNote": null,
  "remote_document_id": "doc_01H..."
}
```

---

### `POST /api/v1/applications/{id}/documents/{documentId}/manual-review`

Request:

```json
{ "reason": "Manual check requested from extraction review." }
```

`reason` is optional. Response `200 OK` with the updated document object.

---

### `POST /api/v1/applications/{id}/documents/share-consent`

Explicit, user-confirmed sharing consent. Called only when the traveler ticks a consent box and
taps **Confirm and share**. The backend is the sole authority for consent: it must not treat any
other call as consent, and the client does not persist a local success if this call fails.

Request:

```json
{
  "documentIds": ["passport", "photo", "proof_of_return", "hotel_booking"]
}
```

`documentIds` is a non-empty subset of `handoff.requestedDocumentIds`.

Response `200 OK` with the updated application object (or at least its `handoff`). Success moves
`handoff.status` to `CONFIRMED` with `confirmedDocumentIds` and appends a
`SHARE_CONSENT_CONFIRMED` timeline event. If consent cannot be recorded (e.g. the handoff is no
longer in `AWAITING_CONSENT`), return a `409 Conflict` and keep `handoff.status` unchanged — the
client must render the failure rather than a simulated success.

---

### `POST /api/v1/applications/{id}/appointments/{appointmentId}/respond`

Records the traveler's choice for a proposed agency appointment.

Request body (`response` is one of `ACCEPT`, `CHANGE`, `DECLINE`):

```json
{
  "response": "ACCEPT",
  "slotId": null,
  "reason": null
}
```

- `ACCEPT` → `slotId` must be `null`, `reason` must be `null`. Response sets
  `appointment.status` to `ACCEPTED` (pending agency confirmation) or `CONFIRMED` when the backend
  confirms immediately.
- `CHANGE` → `slotId` must reference one of the `alternatives`; response sets
  `appointment.status` to `CHANGE_REQUESTED`.
- `DECLINE` → `reason` is an optional free-text string; response sets `appointment.status` to
  `DECLINED`.

Response `200 OK` with the updated application object (or at least its `appointment`), appending
an `APPOINTMENT_RESPONDED` timeline event. The client shows `CONFIRMED` only when the backend
returns it; `ACCEPTED` is shown as awaiting agency confirmation and never represented as final.

---

## Behaviour rules the client relies on

1. **Extraction is not acceptance.** `EXTRACTED` means OCR produced fields. Only `ACCEPTED`
   (set by a reviewer) means the agency approved a document. The client never maps one to the
   other.
2. **No silent fabrication.** If extraction data is absent, the UI says processing is pending
   rather than inventing values.
3. **Offline-first.** Every mutation is persisted on-device first; server sync is best-effort
   and never blocks the UI. A failed upload leaves the local file in `UPLOADED` state with a
   clear "pending" message.
4. **Applicant scope is fixed** to Indian ordinary passport holders travelling for tourism. The
   backend must not accept out-of-scope applicants for this dataset.
5. **Human review is authoritative.** Low-confidence fields and policy violations raise
   `NEEDS_REVIEW`/`POTENTIAL_ISSUE`; they never auto-approve or auto-reject.
6. **Consent is never implied.** `share-consent` is the only way `handoff.status` becomes
   `CONFIRMED`. The client never simulates consent locally; when the backend is unreachable the
   UI shows an error instead of a fake confirmation.
7. **Appointment status is authoritative.** The client treats `CONFIRMED` as final only when the
   backend returns it. `ACCEPTED` is rendered as awaiting agency confirmation, and any failed
   `respond` call leaves the existing status untouched and shows an error.
