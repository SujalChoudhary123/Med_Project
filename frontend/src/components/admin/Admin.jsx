import { useCallback, useEffect, useState } from "react";
import "../business.css";
import { api } from "../../config.js";
import { formatInvoiceDate } from "../../utils/invoiceUtils.js";
import { friendlyError, jsonPost, readJson } from "../../utils/apiHelpers.js";

const STATE_LABEL = {
  free: "Free",
  trial: "Trial",
  active: "Paid",
  grace: "Grace",
  expired: "Ended",
  disabled: "Suspended",
};

/** Platform owner only (ADMIN_EMAILS on the server): every shop, and renewals. */
function Admin() {
  const [data, setData] = useState({ shops: [], summary: { shops: 0, paying: 0, trial: 0, ended: 0 } });
  const [search, setSearch] = useState("");
  const [message, setMessage] = useState({ type: "", text: "" });
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      setData(await fetch(api("/api/admin/shops")).then(readJson));
    } catch (problem) {
      setMessage({ type: "error", text: friendlyError(problem) });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = setTimeout(load, 0);
    return () => clearTimeout(timer);
  }, [load]);

  const update = async (shop, body, done) => {
    try {
      await fetch(api(`/api/admin/shops/${shop.id}`), jsonPost(body)).then(readJson);
      setMessage({ type: "success", text: `${shop.name}: ${done}` });
      load();
    } catch (problem) {
      setMessage({ type: "error", text: friendlyError(problem) });
    }
  };

  const text = search.trim().toLowerCase();
  const shops = data.shops.filter(
    (shop) =>
      !text ||
      [shop.name, shop.owner, shop.email, shop.phone].join(" ").toLowerCase().includes(text)
  );

  return (
    <div className="bill-page">
      {message.text && (
        <div className={`bill-message ${message.type}`} role="status">
          {message.text}
          <button type="button" aria-label="Dismiss" onClick={() => setMessage({ type: "", text: "" })}>×</button>
        </div>
      )}

      <section className="admin-stats">
        {[
          ["Shops", data.summary.shops],
          ["Paying", data.summary.paying],
          ["Free / trial", data.summary.trial],
          ["Ended", data.summary.ended],
        ].map(([label, value]) => (
          <div className="stat-card" key={label}>
            <div>
              <span>{label}</span>
              <strong>{value}</strong>
            </div>
          </div>
        ))}
      </section>

      <section className="dashboard-card">
        <div className="card-header">
          <div>
            <h2>All shops</h2>
            <p>Add days after a shop pays. A suspended shop cannot sign in to its data.</p>
          </div>
          <input
            type="search"
            className="bill-history-search"
            value={search}
            placeholder="Search shop, owner, email"
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>

        {loading ? (
          <div className="bill-empty small">Loading...</div>
        ) : shops.length === 0 ? (
          <div className="bill-empty small">No shops match.</div>
        ) : (
          <div className="bill-table-wrap">
            <table className="bill-table history cards">
              <thead>
                <tr>
                  <th className="left">Shop</th>
                  <th className="left">Owner</th>
                  <th>Status</th>
                  <th className="left">Valid until</th>
                  <th>Bills</th>
                  <th className="left">Last bill</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {shops.map((shop) => (
                  <tr key={shop.id}>
                    <td className="left lead">
                      <strong>{shop.name}</strong>
                      <small>{shop.medicines} medicine{shop.medicines === 1 ? "" : "s"} · {shop.staff} staff{shop.phone ? ` · ${shop.phone}` : ""}</small>
                    </td>
                    <td className="left" data-label="Owner">{shop.owner}<br /><small>{shop.email}</small></td>
                    <td data-label="Status">
                      <span className={`plan-pill ${shop.state}`}>{STATE_LABEL[shop.state] || shop.state}</span>
                    </td>
                    <td className="left" data-label="Valid until">{shop.endsOn || "-"}</td>
                    <td data-label="Bills">{shop.bills}</td>
                    <td className="left" data-label="Last bill">{shop.lastBill ? formatInvoiceDate(shop.lastBill) : "-"}</td>
                    <td className="right action">
                      <div className="admin-actions">
                        <button type="button" className="secondary-button" onClick={() => update(shop, { extendDays: 30 }, "30 days added")}>+30 days</button>
                        <button type="button" className="secondary-button" onClick={() => update(shop, { extendDays: 365 }, "365 days added")}>+1 year</button>
                        <button
                          type="button"
                          className={shop.state === "disabled" ? "secondary-button" : "delete-button"}
                          onClick={() => {
                            const next = shop.state !== "disabled";
                            if (next && !window.confirm(`Suspend ${shop.name}? They will not be able to open their data.`)) return;
                            update(shop, { disabled: next }, next ? "suspended" : "active again");
                          }}
                        >
                          {shop.state === "disabled" ? "Restore" : "Suspend"}
                        </button>
                      </div>
                    </td>
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

export default Admin;
