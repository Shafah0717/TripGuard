import { Navigate, Route, Routes } from "react-router-dom";
import { Layout } from "./components/Layout";
import { NotFoundPage } from "./pages/NotFoundPage";
import { AgencyLoginPage } from "./pages/AgencyLoginPage";
import { AgencyDashboardPage } from "./pages/AgencyDashboardPage";
import { AgencyInquiriesPage } from "./pages/AgencyInquiriesPage";
import { AgencyInquiryDetailPage } from "./pages/AgencyInquiryDetailPage";
import { AgencySlotsPage } from "./pages/AgencySlotsPage";
import { AgencyCalendarPage } from "./pages/AgencyCalendarPage";
import { AgencyBookingsPage } from "./pages/AgencyBookingsPage";

export function App() {
  return (
    <Routes>
      {/* Redirect root to agency dashboard */}
      <Route index element={<Navigate to="/dashboard" replace />} />

      {/* Agency dashboard (real API-backed) */}
      <Route path="/login" element={<AgencyLoginPage />} />
      <Route element={<Layout />}>
        <Route path="dashboard" element={<AgencyDashboardPage />} />
        <Route path="dashboard/inquiries" element={<AgencyInquiriesPage />} />
        <Route path="dashboard/inquiries/:inquiryId" element={<AgencyInquiryDetailPage />} />
        <Route path="dashboard/slots" element={<AgencySlotsPage />} />
        <Route path="dashboard/calendar" element={<AgencyCalendarPage />} />
        <Route path="dashboard/bookings" element={<AgencyBookingsPage />} />
        <Route path="dashboard/*" element={<Navigate to="/dashboard" replace />} />
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
