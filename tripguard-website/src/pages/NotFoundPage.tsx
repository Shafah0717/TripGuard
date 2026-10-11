import { Link } from "react-router-dom";

export function NotFoundPage() {
  return (
    <div className="card">
      <h1>Page not found</h1>
      <p>The page you requested does not exist in the agency console.</p>
      <Link className="btn" to="/">
        Back to dashboard
      </Link>
    </div>
  );
}
