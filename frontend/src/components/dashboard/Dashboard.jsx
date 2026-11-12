import { useCallback, useEffect, useMemo, useState } from "react";
import "./dashboard.css";
import StatCard from "./StatCard.jsx";
import GettingStarted from "./GettingStarted.jsx";
import Icon from "../layout/Icons.jsx";
import { api } from "../../config.js";
import {
  API_URL,
  SALES_URL,
  convertFromINR,
  formatMoney,
  getAppSettings,
  getCurrencySymbol,
} from "../../utils/appUtils.js";

/* ---------- helpers ---------- */

const parseDate = (value) => {
  if (!value) return null;
  const text = String(value);
  const date = new Date(
    text.length <= 10 ? `${text}T00:00:00` : text.replace(" ", "T")
  );
  return Number.isNaN(date.getTime()) ? null : date;
};

const dayKey = (date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(
    date.getDate()
  ).padStart(2, "0")}`;

const formatDate = (value) => {
  const date = parseDate(value);
  if (!date) return "—";
  return date.toLocaleDateString("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
  });
};

const compactMoney = (inr) =>
  `${getCurrencySymbol()}${new Intl.NumberFormat("en-IN", {
    notation: "compact",
    maximumFractionDigits: 1,
  }).format(convertFromINR(inr))}`;

const greetingFor = (hour) => {
  if (hour < 12) return "Good morning";
  if (hour < 17) return "Good afternoon";
  return "Good evening";
};

const STATUS_META = {
  healthy: { label: "Healthy", color: "#10b981" },
  low: { label: "Low stock", color: "#eab308" },
  expiring: { label: "Expiring soon", color: "#f97316" },
  expired: { label: "Expired", color: "#ef4444" },
};

function Dashboard({
  isOwner,
  onOpenSettings,
  onOpenTeam,
  onOpenHelp,
  username = "",
  pharmacyName = "",
  canEdit = true,
  onAddMedicine,
  onViewInventory,
  onNewSale,
  onViewAlerts,
}) {
  const [medicines, setMedicines] = useState([]);
  const [sales, setSales] = useState([]);
  const [invoices, setInvoices] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const settings = getAppSettings();
  const lowStockLimit = Number(settings.lowStockLimit);
  const expiryWarningDays = Number(settings.expiryWarningDays);

  /* ---------- data ---------- */

  const fetchDashboardData = useCallback(async () => {
    try {
      setLoading(true);
      setError("");

      const [medicineResponse, salesResponse, invoiceResponse] = await Promise.all([
        fetch(API_URL),
        fetch(SALES_URL),
        fetch(api("/api/invoices?limit=200")),
      ]);

      if (!medicineResponse.ok) {
        throw new Error(`Server returned ${medicineResponse.status}`);
      }

      const medicineData = await medicineResponse.json();
      setMedicines(Array.isArray(medicineData) ? medicineData : []);

      // Sales are a bonus: the dashboard still works without them.
      if (salesResponse.ok) {
        const salesData = await salesResponse.json().catch(() => []);
        setSales(Array.isArray(salesData) ? salesData : []);
      } else {
        setSales([]);
      }

      // Bills are counted from the invoices themselves (a bill can have many items).
      if (invoiceResponse.ok) {
        const invoiceData = await invoiceResponse.json().catch(() => []);
        setInvoices(Array.isArray(invoiceData) ? invoiceData : []);
      } else {
        setInvoices([]);
      }
    } catch (err) {
      console.error("Dashboard fetch error:", err);
      setError(
        "Unable to load dashboard data. Make sure the Java API server is running."
      );
      setMedicines([]);
      setSales([]);
      setInvoices([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = setTimeout(fetchDashboardData, 0);
    return () => clearTimeout(timer);
  }, [fetchDashboardData]);

  useEffect(() => {
    window.addEventListener("medtrack-auto-refresh", fetchDashboardData);
    return () =>
      window.removeEventListener("medtrack-auto-refresh", fetchDashboardData);
  }, [fetchDashboardData]);

  /* ---------- calculations ---------- */

  const stats = useMemo(() => {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const warnUntil = new Date(today);
    warnUntil.setDate(warnUntil.getDate() + expiryWarningDays);

    const counts = { healthy: 0, low: 0, expiring: 0, expired: 0 };
    const attention = [];
    let lowStockCount = 0;
    let expiringCount = 0;
    let inStock = 0;

    medicines.forEach((medicine) => {
      const quantity = Number(medicine.quantity);
      const expiry = parseDate(medicine.expiryDate || medicine.expiry);

      if (quantity > 0) inStock += 1;
      if (quantity <= lowStockLimit) lowStockCount += 1;
      if (expiry && expiry >= today && expiry <= warnUntil) expiringCount += 1;

      let status = "healthy";
      if (expiry && expiry < today) status = "expired";
      else if (expiry && expiry <= warnUntil) status = "expiring";
      else if (quantity <= lowStockLimit) status = "low";

      counts[status] += 1;

      if (status !== "healthy") {
        const daysLeft = expiry
          ? Math.round((expiry - today) / 86400000)
          : null;

        attention.push({
          medicine,
          status,
          quantity,
          expiry,
          daysLeft,
          rank:
            status === "expired"
              ? 0
              : status === "expiring"
                ? 1 + (daysLeft ?? 0) / 1000
                : 2 + quantity / 1000,
        });
      }
    });

    attention.sort((a, b) => a.rank - b.rank);

    /* sales: today + last 7 days */
    const days = Array.from({ length: 7 }, (_, index) => {
      const date = new Date(today);
      date.setDate(date.getDate() - (6 - index));
      return {
        key: dayKey(date),
        date,
        label: date.toLocaleDateString("en-IN", { weekday: "short" }),
        total: 0,
        count: 0,
      };
    });
    const dayMap = Object.fromEntries(days.map((day) => [day.key, day]));

    sales.forEach((sale) => {
      const date = parseDate(sale.saleDate);
      if (!date) return;
      const bucket = dayMap[dayKey(date)];
      if (bucket) {
        // Revenue is the sum of every sale line, including the negative
        // lines created by returns, so it is already net of refunds.
        bucket.total += Number(sale.totalAmount || 0);
      }
    });

    invoices.forEach((invoice) => {
      if (invoice.status !== "active") return;
      const date = parseDate(invoice.date);
      if (!date) return;
      const bucket = dayMap[dayKey(date)];
      if (bucket) bucket.count += 1;
    });

    const todayBucket = days[days.length - 1];
    const weekTotal = days.reduce((sum, day) => sum + day.total, 0);
    const weekCount = days.reduce((sum, day) => sum + day.count, 0);
    const maxDay = Math.max(...days.map((day) => day.total), 0);

    const latest = [...medicines]
      .sort(
        (a, b) =>
          (parseDate(b.mfgDate)?.getTime() ?? 0) -
          (parseDate(a.mfgDate)?.getTime() ?? 0)
      )
      .slice(0, 6);

    return {
      counts,
      attention,
      lowStockCount,
      expiringCount,
      inStock,
      days,
      todayBucket,
      weekTotal,
      weekCount,
      maxDay,
      latest,
    };
  }, [medicines, sales, invoices, lowStockLimit, expiryWarningDays]);

  const total = medicines.length;
  const attentionCount = stats.attention.length;
  const now = new Date();

  const heroMessage = loading
    ? "Loading your latest numbers..."
    : total === 0
      ? canEdit
        ? "Your inventory is empty. Add your first medicine to get started."
        : "Your inventory is empty. Ask your pharmacist or owner to add medicines."
      : attentionCount === 0
        ? "Everything looks healthy. No stock or expiry issues right now."
        : `${attentionCount} medicine${attentionCount === 1 ? "" : "s"} need${
            attentionCount === 1 ? "s" : ""
          } your attention today.`;

  /* ---------- render ---------- */

  return (
    <div className="dash">
      {error && (
        <div className="dash-error" role="alert">
          <span>{error}</span>
          <button type="button" onClick={fetchDashboardData}>
            Retry
          </button>
        </div>
      )}

      {/* ---------- hero ---------- */}
      {isOwner && !loading && (
        <GettingStarted
          medicineCount={medicines.length}
          billCount={invoices.length}
          onOpenSettings={onOpenSettings}
          onAddMedicine={onAddMedicine}
          onNewSale={onNewSale}
          onOpenTeam={onOpenTeam}
          onOpenHelp={onOpenHelp}
        />
      )}

      <section className="dash-hero">
        <div className="dash-hero-text">
          <span className="dash-hero-date">
            {pharmacyName ? `${pharmacyName} · ` : ""}
            {now.toLocaleDateString("en-IN", {
              weekday: "long",
              day: "numeric",
              month: "long",
            })}
          </span>
          <h2>
            {greetingFor(now.getHours())}
            {username ? `, ${username}` : ""}
          </h2>
          <p>{heroMessage}</p>
        </div>

        <div className="dash-hero-actions">
          <button type="button" className="dash-btn light" onClick={onNewSale}>
            <Icon name="receipt" size={17} />
            New sale
          </button>
          {canEdit && (
            <button
              type="button"
              className="dash-btn ghost"
              onClick={onAddMedicine}
            >
              <Icon name="plus" size={17} />
              Add medicine
            </button>
          )}
        </div>
      </section>

      {/* ---------- stat cards ---------- */}
      <section className="stats-grid dash-stats">
        <StatCard
          icon={<Icon name="pill" size={21} />}
          iconClass="green"
          title="Total Medicines"
          value={total}
          description={`${stats.inStock} currently in stock`}
          loading={loading}
        />
        <StatCard
          icon={<Icon name="wallet" size={21} />}
          iconClass="blue"
          title="Today's Sales"
          value={formatMoney(stats.todayBucket.total)}
          description={`${stats.todayBucket.count} bill${
            stats.todayBucket.count === 1 ? "" : "s"
          } today`}
          loading={loading}
        />
        <StatCard
          icon={<Icon name="alert" size={21} />}
          iconClass="orange"
          title="Low Stock"
          value={stats.lowStockCount}
          description={`${lowStockLimit} units or fewer`}
          loading={loading}
        />
        <StatCard
          icon={<Icon name="clock" size={21} />}
          iconClass="red"
          title="Expiring Soon"
          value={stats.expiringCount}
          description={`Within ${expiryWarningDays} days`}
          loading={loading}
        />
      </section>

      {/* ---------- sales chart + inventory health ---------- */}
      <section className="dash-grid">
        <div className="dashboard-card">
          <div className="card-header">
            <div>
              <h2>Sales overview</h2>
              <p>Revenue for the last 7 days</p>
            </div>
            <div className="dash-chart-total">
              <strong>{formatMoney(stats.weekTotal)}</strong>
              <span>
                {stats.weekCount} bill{stats.weekCount === 1 ? "" : "s"} this
                week
              </span>
            </div>
          </div>

          {loading ? (
            <div className="dash-skeleton dash-chart-skeleton" />
          ) : stats.weekTotal === 0 ? (
            <div className="dash-chart-empty">
              <div className="empty-icon">
                <Icon name="trend" size={24} />
              </div>
              <h3>No sales in the last 7 days</h3>
              <p>Create your first bill and your sales trend will show here.</p>
              <button
                type="button"
                className="primary-button"
                onClick={onNewSale}
              >
                Create a sale
              </button>
            </div>
          ) : (
            <div className="dash-chart" role="img" aria-label="Sales for the last 7 days">
              {stats.days.map((day, index) => {
                const height =
                  stats.maxDay > 0 ? (day.total / stats.maxDay) * 100 : 0;
                const isToday = index === stats.days.length - 1;

                return (
                  <div className="dash-bar-col" key={day.key} tabIndex={0}>
                    <div className="dash-bar-track">
                      {day.total > 0 && (
                        <span className="dash-bar-value">
                          {compactMoney(day.total)}
                        </span>
                      )}
                      <div
                        className={`dash-bar${isToday ? " today" : ""}${
                          day.total === 0 ? " empty" : ""
                        }`}
                        style={{ height: `${Math.max(height, 2)}%` }}
                      />
                    </div>
                    <span className={`dash-bar-label${isToday ? " today" : ""}`}>
                      {isToday ? "Today" : day.label}
                    </span>
                    <div className="dash-tip">
                      <strong>
                        {day.date.toLocaleDateString("en-IN", {
                          day: "numeric",
                          month: "short",
                        })}
                      </strong>
                      <span>{formatMoney(day.total)}</span>
                      <span>
                        {day.count} bill{day.count === 1 ? "" : "s"}
                      </span>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        <div className="dashboard-card">
          <div className="card-header">
            <div>
              <h2>Inventory health</h2>
              <p>How your {total} medicines are doing</p>
            </div>
          </div>

          {loading ? (
            <div className="dash-skeleton dash-health-skeleton" />
          ) : total === 0 ? (
            <div className="dash-mini-empty">No medicines yet.</div>
          ) : (
            <>
              <div className="dash-health-bar" role="img" aria-label="Inventory health breakdown">
                {Object.keys(STATUS_META).map((key) =>
                  stats.counts[key] > 0 ? (
                    <span
                      key={key}
                      style={{
                        width: `${(stats.counts[key] / total) * 100}%`,
                        background: STATUS_META[key].color,
                      }}
                    />
                  ) : null
                )}
              </div>

              <ul className="dash-legend">
                {Object.keys(STATUS_META).map((key) => (
                  <li key={key}>
                    <span
                      className="dash-dot"
                      style={{ background: STATUS_META[key].color }}
                    />
                    <span className="dash-legend-label">
                      {STATUS_META[key].label}
                    </span>
                    <strong>{stats.counts[key]}</strong>
                    <span className="dash-legend-pct">
                      {Math.round((stats.counts[key] / total) * 100)}%
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      </section>

      {/* ---------- latest batches + needs attention ---------- */}
      <section className="dash-grid reverse">
        <div className="dashboard-card">
          <div className="card-header">
            <div>
              <h2>Latest batches</h2>
              <p>Most recently manufactured stock</p>
            </div>
            <button type="button" className="view-button" onClick={onViewInventory}>
              View all
              <Icon name="arrow" size={14} />
            </button>
          </div>

          {loading ? (
            <div className="dash-skeleton dash-table-skeleton" />
          ) : stats.latest.length === 0 ? (
            <div className="empty-state">
              <div className="empty-icon">
                <Icon name="pill" size={24} />
              </div>
              <h3>No medicines yet</h3>
              <p>
                {canEdit
                  ? "Add medicines to see them here."
                  : "Ask your pharmacist or owner to add medicines."}
              </p>
              {canEdit && (
                <button type="button" className="primary-button" onClick={onAddMedicine}>
                  + Add Medicine
                </button>
              )}
            </div>
          ) : (
            <div className="dash-table-wrap">
              <table className="dash-table">
                <thead>
                  <tr>
                    <th>Medicine</th>
                    <th>Category</th>
                    <th className="center">Stock</th>
                    <th className="right">Expiry</th>
                  </tr>
                </thead>
                <tbody>
                  {stats.latest.map((medicine) => {
                    const quantity = Number(medicine.quantity);
                    return (
                      <tr key={medicine.id}>
                        <td>
                          <div className="dash-med-name">{medicine.name}</div>
                          <div className="dash-med-id">{medicine.id}</div>
                        </td>
                        <td>
                          {medicine.category ? (
                            <span className="category-badge">
                              {medicine.category}
                            </span>
                          ) : (
                            "—"
                          )}
                        </td>
                        <td className="center">
                          <span
                            className={
                              quantity <= lowStockLimit
                                ? "quantity-badge low"
                                : "quantity-badge"
                            }
                          >
                            {quantity}
                          </span>
                        </td>
                        <td className="right">
                          {formatDate(medicine.expiryDate || medicine.expiry)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="dashboard-card">
          <div className="card-header">
            <div>
              <h2>Needs attention</h2>
              <p>Items to restock or sell first</p>
            </div>
            {attentionCount > 0 && (
              <button type="button" className="view-button" onClick={onViewAlerts}>
                All alerts
                <Icon name="arrow" size={14} />
              </button>
            )}
          </div>

          {loading ? (
            <div className="dash-skeleton dash-table-skeleton" />
          ) : attentionCount === 0 ? (
            <div className="dash-all-clear">
              <span>
                <Icon name="check" size={22} />
              </span>
              <strong>All clear</strong>
              <p>No low-stock, expiring or expired medicines.</p>
            </div>
          ) : (
            <ul className="dash-attention">
              {stats.attention.slice(0, 6).map((item) => {
                const { medicine, status, quantity, daysLeft } = item;
                const detail =
                  status === "expired"
                    ? `Expired ${formatDate(medicine.expiryDate || medicine.expiry)}`
                    : status === "expiring"
                      ? daysLeft === 0
                        ? "Expires today"
                        : `Expires in ${daysLeft} day${daysLeft === 1 ? "" : "s"}`
                      : quantity === 0
                        ? "Out of stock"
                        : `Only ${quantity} left`;

                return (
                  <li key={medicine.id}>
                    <span className={`dash-attn-icon ${status}`}>
                      <Icon
                        name={
                          status === "expired"
                            ? "cross"
                            : status === "expiring"
                              ? "clock"
                              : "alert"
                        }
                        size={18}
                      />
                    </span>
                    <div className="dash-attn-main">
                      <strong>{medicine.name}</strong>
                      <span>
                        {medicine.id}
                        {medicine.batchNumber ? ` · Batch ${medicine.batchNumber}` : ""}
                      </span>
                    </div>
                    <span className={`dash-pill ${status}`}>{detail}</span>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </section>
    </div>
  );
}

export default Dashboard;
