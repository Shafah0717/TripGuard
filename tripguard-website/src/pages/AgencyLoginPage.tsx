import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  AgencyApiError,
  AgencyNetworkError,
  getStoredToken,
  login,
} from "../services/agencyApi";

export function AgencyLoginPage() {
  // Already signed in? Straight to the dashboard.
  if (getStoredToken()) {
    window.location.replace("/dashboard");
  }

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const result = await login(username, password);
      sessionStorage.setItem("tripguard_agency_token", result.access_token);
      navigate("/dashboard", { replace: true });
    } catch (caught) {
      if (caught instanceof AgencyNetworkError) setError(caught.message);
      else if (caught instanceof AgencyApiError) setError(caught.message);
      else setError("Sign-in failed unexpectedly.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-shell">
      <div className="card login-card">
        <h1>Staff sign-in</h1>
        <p className="muted">
          Agency access only. Travellers reach their inquiries and bookings from
          the TripGuard Android app, not this console.
        </p>
        {error ? <div className="notice notice--error">{error}</div> : null}
        <form onSubmit={submit} className="stack">
          <div className="field">
            <label htmlFor="username">Username</label>
            <input
              id="username"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              required
            />
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
          </div>
          <button className="btn" type="submit" disabled={busy}>
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>
        <p className="small muted" style={{ marginTop: "0.75rem" }}>
          Development demo login: <code>agency_admin</code> /{" "}
          <code>tripguard-demo-password</code> (seeded via the development-only
          seed script).
        </p>
      </div>
    </div>
  );
}
