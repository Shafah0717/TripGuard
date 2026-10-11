import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { getStoredToken, storeToken } from "../services/agencyApi";

const agencyLinks = [
  { to: "/dashboard", label: "Dashboard", end: true },
  { to: "/dashboard/inquiries", label: "Inquiries" },
  { to: "/dashboard/calendar", label: "Calendar" },
  { to: "/dashboard/slots", label: "Slots" },
  { to: "/dashboard/bookings", label: "Bookings" },
];

export function Layout() {
  const token = getStoredToken();
  const navigate = useNavigate();

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="topbar__inner">
          <NavLink to="/dashboard" className="brand">
            <span className="brand__mark" aria-hidden="true">
              TG
            </span>
            <span>
              TripGuard
              <span className="brand__sub">Agency Visa Console</span>
            </span>
          </NavLink>
          <nav className="nav" aria-label="Agency">
            {agencyLinks.map((link) => (
              <NavLink
                key={link.to}
                to={link.to}
                end={link.end}
                className={({ isActive }) =>
                  `nav__link${isActive ? " nav__link--active" : ""}`
                }
              >
                {link.label}
              </NavLink>
            ))}
          </nav>
          <div className="nav">
            {token ? (
              <button
                type="button"
                className="btn btn--secondary btn--small"
                onClick={() => {
                  storeToken(null);
                  navigate("/login", { replace: true });
                }}
              >
                Sign out
              </button>
            ) : (
              <NavLink to="/login" className="nav__link nav__link--active">
                Staff sign-in
              </NavLink>
            )}
          </div>
        </div>
      </header>
      <main className="page">
        <Outlet />
      </main>
    </div>
  );
}
