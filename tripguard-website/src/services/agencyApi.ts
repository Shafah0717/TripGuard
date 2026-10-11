/**
 * Typed HTTP client for the TripGuard Agency API (FastAPI, port 8000 in dev).
 *
 * - Bearer token kept in sessionStorage only — a new tab or refresh clears it,
 *   so staff credentials never persist on shared machines.
 * - Network/HTTP failures surface as typed errors so pages can render retry
 *   states instead of pretending success.
 */

/// <reference types="vite/client" />

interface ViteEnv {
  VITE_AGENCY_API_BASE?: string;
}

const API_BASE: string =
  (import.meta as unknown as { env?: ViteEnv }).env?.VITE_AGENCY_API_BASE ??
  "http://localhost:8000";

const TOKEN_KEY = "tripguard_agency_token";

export class AgencyApiError extends Error {
  readonly status: number;
  readonly detail: string | null;

  constructor(status: number, message: string, detail: string | null = null) {
    super(message);
    this.name = "AgencyApiError";
    this.status = status;
    this.detail = detail;
  }
}

export class AgencyNetworkError extends Error {
  constructor() {
    super("Could not reach the agency API. Check your connection and retry.");
    this.name = "AgencyNetworkError";
  }
}

export function getStoredToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function storeToken(token: string | null): void {
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token);
    else sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    /* storage unavailable; token stays in memory only */
  }
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  auth?: boolean;
  query?: Record<string, string | undefined>;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (options.auth !== false) {
    const token = getStoredToken();
    if (token) headers.Authorization = `Bearer ${token}`;
  }

  let url = `${API_BASE}${path}`;
  if (options.query) {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(options.query)) {
      if (value !== undefined && value !== "") params.set(key, value);
    }
    const qs = params.toString();
    if (qs) url += `?${qs}`;
  }

  let response: Response;
  try {
    response = await fetch(url, {
      method: options.method ?? "GET",
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    });
  } catch {
    throw new AgencyNetworkError();
  }

  if (!response.ok) {
    let detail: string | null = null;
    let message = `Request failed (HTTP ${response.status}).`;
    try {
      const payload = (await response.json()) as { detail?: unknown };
      if (typeof payload.detail === "string") {
        detail = payload.detail;
        message = payload.detail;
      } else if (Array.isArray(payload.detail)) {
        // FastAPI validation errors: join the human-readable messages.
        detail = payload.detail
          .map((item: { msg?: string }) => item?.msg ?? "")
          .filter(Boolean)
          .join("; ");
        message = detail;
      }
    } catch {
      /* non-JSON error body */
    }
    throw new AgencyApiError(response.status, message, detail);
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

// ---- auth ----

export interface LoginResult {
  access_token: string;
  role: string;
}

export function login(username: string, password: string): Promise<LoginResult> {
  return request<LoginResult>("/api/agency/login", {
    method: "POST",
    body: { username, password },
    auth: false,
  });
}

// ---- dashboard ----

import type {
  AgencyBooking,
  AgencyInquiry,
  AgencyNoteDto,
  AgencySlot,
  DashboardStats,
} from "../types/agency";

export function fetchDashboardStats(): Promise<DashboardStats> {
  return request<DashboardStats>("/api/agency/dashboard");
}

export function fetchInquiries(params?: {
  status?: string;
  search?: string;
}): Promise<AgencyInquiry[]> {
  return request<AgencyInquiry[]>("/api/agency/inquiries", {
    query: { status: params?.status, search: params?.search },
  });
}

export function fetchInquiry(id: number): Promise<AgencyInquiry> {
  return request<AgencyInquiry>(`/api/agency/inquiries/${id}`);
}

export function updateInquiryStatus(
  id: number,
  status: string,
): Promise<AgencyInquiry> {
  return request<AgencyInquiry>(`/api/agency/inquiries/${id}`, {
    method: "PATCH",
    body: { status },
  });
}

export function addInquiryNote(
  id: number,
  note: string,
  author: string,
): Promise<AgencyInquiry> {
  return request<AgencyInquiry>(`/api/agency/inquiries/${id}`, {
    method: "PATCH",
    body: { note, author },
  });
}

export function fetchInquiryNotes(id: number): Promise<AgencyNoteDto[]> {
  return request<AgencyNoteDto[]>(`/api/agency/inquiries/${id}/notes`);
}

export interface MessageResult {
  message: string;
}

/** Permanently removes an inquiry and its linked slots/bookings/notes. */
export function deleteInquiry(id: number): Promise<MessageResult> {
  return request<MessageResult>(`/api/agency/inquiries/${id}`, { method: "DELETE" });
}

// ---- slots ----

export interface SlotInput {
  start_at: string;
  end_at: string;
  timezone_name: string;
  location: string;
  capacity: number;
  published: boolean;
  notes?: string | undefined;
  /** Offer this slot to one specific inquiry. */
  inquiry_id?: number | undefined;
}

export function fetchSlots(): Promise<AgencySlot[]> {
  return request<AgencySlot[]>("/api/agency/slots");
}

/** Slots explicitly offered to one inquiry (published or not). */
export function fetchInquirySlots(inquiryId: number): Promise<AgencySlot[]> {
  return request<AgencySlot[]>(`/api/agency/inquiries/${inquiryId}/slots`);
}

export function createSlot(input: SlotInput): Promise<AgencySlot> {
  return request<AgencySlot>("/api/agency/slots", { method: "POST", body: input });
}

export function updateSlot(id: number, patch: Partial<SlotInput> & { closed?: boolean }): Promise<AgencySlot> {
  return request<AgencySlot>(`/api/agency/slots/${id}`, { method: "PATCH", body: patch });
}

export function fetchSlotBookings(slotId: number): Promise<AgencyBooking[]> {
  return request<AgencyBooking[]>(`/api/agency/slots/${slotId}/bookings`);
}

// ---- bookings ----

export function fetchBookings(params?: { date?: string }): Promise<AgencyBooking[]> {
  return request<AgencyBooking[]>("/api/agency/bookings", { query: { date: params?.date } });
}

export function updateBooking(
  id: number,
  patch: { status?: string; note?: string },
): Promise<AgencyBooking> {
  return request<AgencyBooking>(`/api/agency/bookings/${id}`, {
    method: "PATCH",
    body: patch,
  });
}
