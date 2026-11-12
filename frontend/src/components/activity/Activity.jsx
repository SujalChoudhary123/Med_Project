import { useCallback, useEffect, useState } from "react";
import "../business.css";
import { api } from "../../config.js";
import { formatInvoiceDate } from "../../utils/invoiceUtils.js";
import { downloadFile } from "../../utils/download.js";
import { friendlyError, readJson } from "../../utils/apiHelpers.js";

const AREAS = [
  ["", "Everything"],
  ["invoice", "Bills and returns"],
  ["medicine", "Medicines"],
  ["purchase", "Purchases"],
  ["customer", "Customers"],
  ["supplier", "Suppliers"],
  ["team", "Team"],
  ["account", "Sign-ins and passwords"],
  ["shop", "Shop details"],
];

const LABELS = {
  "invoice.create": "Bill made",
  "invoice.cancel": "Bill cancelled",
  "invoice.return": "Return / credit note",
  "sale.create": "Sale recorded",
  "medicine.add": "Medicine added",
  "medicine.update": "Medicine edited",
  "medicine.delete": "Medicine deleted",
  "medicine.import": "Medicines imported",
  "purchase.create": "Stock received",
  "purchase.cancel": "Purchase cancelled",
  "customer.add": "Customer added",
  "customer.update": "Customer edited",
  "customer.delete": "Customer deleted",
  "customer.payment": "Payment received",
  "supplier.add": "Supplier added",
  "supplier.update": "Supplier edited",
  "supplier.delete": "Supplier deleted",
  "supplier.payment": "Supplier paid",
  "team.add": "Staff added",
  "team.update": "Staff role changed",
  "team.delete": "Staff removed",
  "shop.update": "Shop details changed",
  "account.signin": "Signed in",
  "account.update": "Account edited",
  "account.password": "Password changed",
  "data.export": "All data downloaded",
  "plan.update": "Plan changed by MedTrack",
};

/** Owner only: who did what, and a download of everything the shop has stored. */
function Activity() {
  const [area, setArea] = useState("");
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setError("");
      const data = await fetch(api(`/api/audit?limit=300&action=${encodeURIComponent(area)}`)).then(readJson);
      setEntries(data.entries || []);
    } catch (problem) {
      setError(friendlyError(problem));
    } finally {
      setLoading(false);
    }
  }, [area]);

  useEffect(() => {
    const timer = setTimeout(load, 0);
    return () => clearTimeout(timer);
  }, [load]);

  const exportAll = async () => {
    try {
      setBusy(true);
      setError("");
      await downloadFile(api("/api/backup/export"), "medtrack-data.zip");
    } catch (problem) {
      setError(friendlyError(problem));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="bill-page">
      {error && <div className="bill-message error" role="alert">{error}</div>}

      <section className="dashboard-card">
        <div className="card-header">
          <div>
            <h2>Your data</h2>
            <p>Download everything in your shop as Excel-friendly files. Keep a copy every month.</p>
          </div>
          <button type="button" className="primary-button" disabled={busy} onClick={exportAll}>
            {busy ? "Preparing..." : "Download all my data"}
          </button>
        </div>
      </section>

      <section className="dashboard-card">
        <div className="card-header">
          <div>
            <h2>Activity log</h2>
            <p>Who did what in your shop. Entries cannot be edited or removed.</p>
          </div>
          <select
            className="report-select"
            value={area}
            aria-label="Show"
            onChange={(event) => setArea(event.target.value)}
          >
            {AREAS.map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </div>

        {loading ? (
          <div className="bill-empty small">Loading...</div>
        ) : entries.length === 0 ? (
          <div className="bill-empty small">Nothing recorded yet.</div>
        ) : (
          <div className="bill-table-wrap">
            <table className="bill-table history cards">
              <thead>
                <tr>
                  <th className="left">What happened</th>
                  <th className="left">Who</th>
                  <th className="left">When</th>
                  <th className="left">From</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <tr key={entry.id}>
                    <td className="left lead">
                      <strong>{LABELS[entry.action] || entry.action}</strong>
                      {(entry.detail || entry.target) && (
                        <small>{entry.detail || entry.target}</small>
                      )}
                    </td>
                    <td className="left" data-label="Who">{entry.user} ({entry.role})</td>
                    <td className="left" data-label="When">{formatInvoiceDate(entry.at)}</td>
                    <td className="left" data-label="From">{entry.ip || "-"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

export default Activity;
