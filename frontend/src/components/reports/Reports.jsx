import { useCallback, useEffect, useMemo, useState } from "react";
import "./reports.css";
import Icon from "../layout/Icons.jsx";
import ReportStat from "./ReportStat.jsx";
import { api } from "../../config.js";
import { downloadFile } from "../../utils/download.js";
import { getAppSettings } from "../../utils/appUtils.js";
import { inr } from "../../utils/invoiceUtils.js";

/* ---------- periods ---------- */

const iso = (date) =>
  `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(
    date.getDate()
  ).padStart(2, "0")}`;

const PERIODS = [
  { key: "today", label: "Today" },
  { key: "7", label: "Last 7 days" },
  { key: "30", label: "Last 30 days" },
  { key: "month", label: "This month" },
  { key: "lastmonth", label: "Last month" },
  { key: "fy", label: "This financial year" },
  { key: "custom", label: "Custom range" },
];

function rangeFor(key, customFrom, customTo) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const start = new Date(today);

  switch (key) {
    case "today":
      return { from: iso(today), to: iso(today) };
    case "7":
      start.setDate(start.getDate() - 6);
      return { from: iso(start), to: iso(today) };
    case "30":
      start.setDate(start.getDate() - 29);
      return { from: iso(start), to: iso(today) };
    case "month":
      return { from: iso(new Date(today.getFullYear(), today.getMonth(), 1)), to: iso(today) };
    case "lastmonth":
      return {
        from: iso(new Date(today.getFullYear(), today.getMonth() - 1, 1)),
        to: iso(new Date(today.getFullYear(), today.getMonth(), 0)),
      };
    case "fy": {
      // Indian financial year: 1 April to 31 March
      const year = today.getMonth() >= 3 ? today.getFullYear() : today.getFullYear() - 1;
      return { from: iso(new Date(year, 3, 1)), to: iso(today) };
    }
    default:
      return { from: customFrom, to: customTo };
  }
}

/** Groups daily numbers into days, weeks or months so the chart stays readable. */
function buildBars(daily, from, to) {
  if (!from || !to) return [];

  const byDay = Object.fromEntries(daily.map((row) => [row.date, row]));
  const start = new Date(`${from}T00:00:00`);
  const end = new Date(`${to}T00:00:00`);
  const spanDays = Math.round((end - start) / 86400000) + 1;

  const mode = spanDays <= 31 ? "day" : spanDays <= 150 ? "week" : "month";
  const buckets = new Map();

  for (let day = new Date(start); day <= end; day.setDate(day.getDate() + 1)) {
    let key;
    let label;

    if (mode === "day") {
      key = iso(day);
      label = String(day.getDate());
    } else if (mode === "week") {
      const monday = new Date(day);
      monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));
      key = iso(monday);
      label = monday.toLocaleDateString("en-IN", { day: "2-digit", month: "short" });
    } else {
      key = `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, "0")}`;
      label = day.toLocaleDateString("en-IN", { month: "short", year: "2-digit" });
    }

    if (!buckets.has(key)) buckets.set(key, { key, label, net: 0, bills: 0 });

    const row = byDay[iso(day)];
    if (row) {
      const bucket = buckets.get(key);
      bucket.net += row.net;
      bucket.bills += row.bills;
    }
  }

  return [...buckets.values()];
}

const formatDate = (value) => {
  const date = new Date(`${String(value).slice(0, 10)}T00:00:00`);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });
};

const MODE_LABEL = { cash: "Cash", upi: "UPI", card: "Card", credit: "Credit" };

async function getJson(url) {
  const response = await fetch(url);
  const data = await response.json().catch(() => ({}));
  if (!response.ok || data.success === false) {
    throw new Error(data.error || "Could not load the report.");
  }
  return data;
}

function Reports() {
  const settings = getAppSettings();

  const [tab, setTab] = useState("overview");
  const [period, setPeriod] = useState("30");
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  const [expiryDays, setExpiryDays] = useState(90);

  const [summary, setSummary] = useState(null);
  const [stock, setStock] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");

  const range = rangeFor(period, customFrom, customTo);
  const rangeReady = Boolean(range.from && range.to && range.from <= range.to);

  const load = useCallback(async () => {
    if (!rangeReady) return;

    try {
      setLoading(true);
      setError("");

      const [summaryData, stockData] = await Promise.all([
        getJson(api(`/api/reports/summary?from=${range.from}&to=${range.to}`)),
        getJson(
          api(`/api/reports/stock?days=${expiryDays}&low=${Number(settings.lowStockLimit) || 10}`)
        ),
      ]);

      setSummary(summaryData);
      setStock(stockData);
    } catch (problem) {
      setError(problem.message);
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [range.from, range.to, rangeReady, expiryDays]);

  useEffect(() => {
    const timer = setTimeout(load, 0);
    return () => clearTimeout(timer);
  }, [load]);

  useEffect(() => {
    window.addEventListener("medtrack-auto-refresh", load);
    return () => window.removeEventListener("medtrack-auto-refresh", load);
  }, [load]);

  const exportCsv = async (type, label) => {
    try {
      setBusy(type);
      setError("");
      await downloadFile(
        api(`/api/reports/export?type=${type}&from=${range.from}&to=${range.to}&days=${expiryDays}`),
        `medtrack-${type}.csv`
      );
    } catch (problem) {
      setError(`${label}: ${problem.message}`);
    } finally {
      setBusy("");
    }
  };

  const bars = useMemo(
    () => (summary ? buildBars(summary.daily, summary.from, summary.to) : []),
    [summary]
  );
  const maxBar = Math.max(...bars.map((bar) => bar.net), 0);
  // Fewer labels when there are many bars or the screen is narrow.
  const narrow = typeof window !== "undefined" && window.innerWidth < 640;
  const labelEvery = narrow
    ? bars.length > 14 ? 5 : 1
    : bars.length > 40 ? 4 : bars.length > 20 ? 2 : 1;

  const net = summary?.net;
  const sales = summary?.sales;
  const returns = summary?.returns;
  const gstTotal = net ? net.cgst + net.sgst : 0;
  const averageBill = sales && sales.count ? sales.total / sales.count : 0;

  const exportButton = (type, label) => (
    <button
      key={type}
      type="button"
      className="secondary-button"
      disabled={busy === type || !rangeReady}
      onClick={() => exportCsv(type, label)}
    >
      {busy === type ? "Preparing..." : `↓ ${label}`}
    </button>
  );

  return (
    <div className="page-scroll reports-page">
      {error && <div className="report-error">{error}</div>}

      <div className="reports-toolbar">
        <div>
          <h2>Reports &amp; GST</h2>
          <p>
            {rangeReady
              ? `${formatDate(range.from)} to ${formatDate(range.to)}`
              : "Choose a start and end date"}
          </p>
        </div>

        <div className="report-actions">
          <select
            className="report-select"
            value={period}
            onChange={(event) => setPeriod(event.target.value)}
            aria-label="Report period"
          >
            {PERIODS.map((item) => (
              <option key={item.key} value={item.key}>
                {item.label}
              </option>
            ))}
          </select>
          <button type="button" className="secondary-button" onClick={load}>
            ↻ Refresh
          </button>
          <button type="button" className="secondary-button" onClick={() => window.print()}>
            ⎙ Print
          </button>
        </div>
      </div>

      {period === "custom" && (
        <div className="custom-date-row">
          <label>
            From
            <input type="date" value={customFrom} onChange={(e) => setCustomFrom(e.target.value)} />
          </label>
          <label>
            To
            <input type="date" value={customTo} onChange={(e) => setCustomTo(e.target.value)} />
          </label>
        </div>
      )}

      <div className="rep-tabs" role="tablist">
        {[
          ["overview", "Sales overview"],
          ["gst", "GST & tax"],
          ["stock", "Stock & expiry"],
        ].map(([key, label]) => (
          <button
            key={key}
            type="button"
            role="tab"
            aria-selected={tab === key}
            className={tab === key ? "active" : ""}
            onClick={() => setTab(key)}
          >
            {label}
          </button>
        ))}
      </div>

      {/* =============== SALES OVERVIEW =============== */}
      {tab === "overview" && (
        <>
          <div className="report-stat-grid">
            <ReportStat icon={<Icon name="wallet" size={21} />} title="Net sales" value={loading ? "..." : inr(net?.total)} note="After returns" />
            <ReportStat icon={<Icon name="receipt" size={21} />} title="Bills" value={loading ? "..." : sales?.count ?? 0} note={`Average ${inr(averageBill)}`} />
            <ReportStat icon={<Icon name="cross" size={21} />} title="Returns" value={loading ? "..." : inr(returns?.total)} note={`${returns?.count ?? 0} credit note${returns?.count === 1 ? "" : "s"}`} />
            <ReportStat icon={<Icon name="chart" size={21} />} title="Discount given" value={loading ? "..." : inr(sales?.discount)} note="On bills in this period" />
          </div>

          <div className="report-stat-grid">
            <ReportStat icon={<Icon name="trend" size={21} />} title="Gross profit" value={loading ? "..." : inr(summary?.profit.gross)} note={`${summary?.profit.marginPercent ?? 0}% margin`} />
            <ReportStat icon={<Icon name="wallet" size={21} />} title="Sales before GST" value={loading ? "..." : inr(summary?.profit.revenue)} note="After returns" />
            <ReportStat icon={<Icon name="pill" size={21} />} title="Cost of goods sold" value={loading ? "..." : inr(summary?.profit.cost)} note="From cost prices" />
            <ReportStat icon={<Icon name="plus" size={21} />} title="Purchases" value={loading ? "..." : inr(summary?.purchases.total)} note={`${summary?.purchases.count ?? 0} supplier bill${summary?.purchases.count === 1 ? "" : "s"}`} />
          </div>

          {summary && summary.profit.unitsWithoutCost > 0 && (
            <p className="rep-note">
              {summary.profit.unitsWithoutCost} of {summary.profit.unitsSold} units sold have no cost price, so
              the real profit is lower than shown. Enter purchases or add the cost on those medicines.
            </p>
          )}

          <section className="dashboard-card report-card">
            <div className="card-header">
              <div>
                <h2>Sales trend</h2>
                <p>Net sales (after returns) {bars.length > 31 ? "by week or month" : "by day"}</p>
              </div>
            </div>

            {bars.length === 0 || maxBar === 0 ? (
              <div className="report-empty">No sales in this period.</div>
            ) : (
              <div className="rep-chart" style={{ gridTemplateColumns: `repeat(${bars.length}, minmax(0, 1fr))` }}>
                {bars.map((bar, index) => (
                  <div className="rep-bar" key={bar.key} title={`${bar.label}: ${inr(bar.net)} (${bar.bills} bills)`}>
                    <div className="rep-bar-track">
                      <div
                        className="rep-bar-fill"
                        style={{ height: `${Math.max(2, (Math.max(bar.net, 0) / maxBar) * 100)}%` }}
                      />
                    </div>
                    <span>{index % labelEvery === 0 ? bar.label : ""}</span>
                  </div>
                ))}
              </div>
            )}
          </section>

          <div className="report-two-column">
            <section className="dashboard-card report-card">
              <div className="card-header">
                <div>
                  <h2>Top medicines</h2>
                  <p>By revenue, after returns</p>
                </div>
              </div>

              {!summary || summary.top.length === 0 ? (
                <div className="report-empty">No sales found for this period.</div>
              ) : (
                <div className="report-list">
                  {summary.top.map((item, index) => (
                    <div className="report-list-row" key={item.medicineId}>
                      <div className="rank-badge">{index + 1}</div>
                      <div className="report-list-main">
                        <strong>{item.name}</strong>
                        <span>{item.medicineId} · {item.units} units</span>
                        <div className="progress-track">
                          <div
                            className="progress-fill"
                            style={{ width: `${Math.max(5, (item.revenue / summary.top[0].revenue) * 100)}%` }}
                          />
                        </div>
                      </div>
                      <strong>{inr(item.revenue)}</strong>
                    </div>
                  ))}
                </div>
              )}
            </section>

            <section className="dashboard-card report-card">
              <div className="card-header">
                <div>
                  <h2>How customers paid</h2>
                  <p>Money in, refunds out</p>
                </div>
              </div>

              {!summary || summary.paymentModes.length === 0 ? (
                <div className="report-empty">No payments in this period.</div>
              ) : (
                <table className="report-table">
                  <thead>
                    <tr>
                      <th>Mode</th>
                      <th className="num">Sales</th>
                      <th className="num">Refunds</th>
                      <th className="num">Net</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary.paymentModes.map((row) => (
                      <tr key={row.mode}>
                        <td>{MODE_LABEL[row.mode] || row.mode}</td>
                        <td className="num">{inr(row.sales)}</td>
                        <td className="num">{inr(row.refunds)}</td>
                        <td className="num"><strong>{inr(row.net)}</strong></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
          </div>

          <section className="dashboard-card report-card">
            <div className="card-header">
              <div>
                <h2>Download</h2>
                <p>Opens in Excel</p>
              </div>
            </div>
            <div className="rep-downloads">
              {exportButton("register", "Sales register")}
              {exportButton("items", "Item-wise sales")}
              {exportButton("profit", "Profit by medicine")}
              {exportButton("dues", "Customer dues")}
              {exportButton("supplier-dues", "Supplier dues")}
            </div>
          </section>
        </>
      )}

      {/* =============== GST =============== */}
      {tab === "gst" && (
        <>
          <div className="report-stat-grid">
            <ReportStat icon={<Icon name="receipt" size={21} />} title="Taxable value" value={loading ? "..." : inr(net?.taxable)} note="Net of returns" />
            <ReportStat icon={<Icon name="wallet" size={21} />} title="CGST" value={loading ? "..." : inr(net?.cgst)} note="Collected" />
            <ReportStat icon={<Icon name="wallet" size={21} />} title="SGST" value={loading ? "..." : inr(net?.sgst)} note="Collected" />
            <ReportStat icon={<Icon name="chart" size={21} />} title="Total GST" value={loading ? "..." : inr(gstTotal)} note="CGST + SGST" />
          </div>

          <div className="report-stat-grid">
            <ReportStat icon={<Icon name="plus" size={21} />} title="Input GST on purchases" value={loading ? "..." : inr((summary?.purchases.cgst ?? 0) + (summary?.purchases.sgst ?? 0))} note={`On ${inr(summary?.purchases.taxable)} of purchases`} />
            <ReportStat icon={<Icon name="wallet" size={21} />} title="GST collected less input" value={loading ? "..." : inr(gstTotal - (summary?.purchases.cgst ?? 0) - (summary?.purchases.sgst ?? 0))} note="Before your accountant's adjustments" />
          </div>

          <section className="dashboard-card report-card">
            <div className="card-header">
              <div>
                <h2>GST by rate</h2>
                <p>Sales minus returns, cancelled bills left out</p>
              </div>
            </div>

            {!summary || summary.byRate.length === 0 ? (
              <div className="report-empty">No sales in this period.</div>
            ) : (
              <div className="table-wrap">
                <table className="report-table">
                  <thead>
                    <tr>
                      <th>GST rate</th>
                      <th className="num">Taxable value</th>
                      <th className="num">CGST</th>
                      <th className="num">SGST</th>
                      <th className="num">Total</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary.byRate.map((row) => (
                      <tr key={row.rate}>
                        <td>{row.rate}%</td>
                        <td className="num">{inr(row.taxable)}</td>
                        <td className="num">{inr(row.cgst)}</td>
                        <td className="num">{inr(row.sgst)}</td>
                        <td className="num"><strong>{inr(row.total)}</strong></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="dashboard-card report-card">
            <div className="card-header">
              <div>
                <h2>HSN summary</h2>
                <p>Quantity and tax for each HSN code and rate</p>
              </div>
            </div>

            {!summary || summary.hsn.length === 0 ? (
              <div className="report-empty">No sales in this period.</div>
            ) : (
              <div className="table-wrap">
                <table className="report-table">
                  <thead>
                    <tr>
                      <th>HSN</th>
                      <th>Rate</th>
                      <th className="num">Net qty</th>
                      <th className="num">Taxable</th>
                      <th className="num">CGST</th>
                      <th className="num">SGST</th>
                      <th className="num">Total</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary.hsn.map((row) => (
                      <tr key={`${row.hsn}-${row.rate}`}>
                        <td>{row.hsn}</td>
                        <td>{row.rate}%</td>
                        <td className="num">{row.quantity}</td>
                        <td className="num">{inr(row.taxable)}</td>
                        <td className="num">{inr(row.cgst)}</td>
                        <td className="num">{inr(row.sgst)}</td>
                        <td className="num"><strong>{inr(row.total)}</strong></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="dashboard-card report-card">
            <div className="card-header">
              <div>
                <h2>Files for your accountant</h2>
                <p>CSV files that open in Excel. Credit notes appear as negative amounts.</p>
              </div>
            </div>
            <div className="rep-downloads">
              {exportButton("register", "GST sales register")}
              {exportButton("items", "Item-wise sales")}
              {exportButton("hsn", "HSN summary")}
              {exportButton("rate", "Rate-wise summary")}
              {exportButton("purchases", "Purchase register")}
            </div>
            <p className="rep-note">
              These figures come from your bills. Please ask your accountant to check them before you
              file any GST return.
            </p>
          </section>
        </>
      )}

      {/* =============== STOCK =============== */}
      {tab === "stock" && (
        <>
          <div className="report-stat-grid">
            <ReportStat icon={<Icon name="pill" size={21} />} title="Medicines" value={loading ? "..." : stock?.medicines ?? 0} note="Different items" />
            <ReportStat icon={<Icon name="receipt" size={21} />} title="Units in stock" value={loading ? "..." : (stock?.units ?? 0).toLocaleString("en-IN")} note="All batches" />
            <ReportStat icon={<Icon name="wallet" size={21} />} title="Stock value" value={loading ? "..." : inr(stock?.value)} note="At MRP" />
            <ReportStat icon={<Icon name="alert" size={21} />} title="Low stock" value={loading ? "..." : stock?.low.length ?? 0} note={`${stock?.lowLimit ?? 10} units or fewer`} />
          </div>

          <div className="report-two-column">
            <StockList
              title="Expired"
              hint="Remove these from the shelf"
              tone="danger"
              items={stock?.expired}
            />
            <StockList
              title="Expiring soon"
              hint={
                <>
                  Within{" "}
                  <select
                    className="rep-inline-select"
                    value={expiryDays}
                    onChange={(event) => setExpiryDays(Number(event.target.value))}
                    aria-label="Expiring within days"
                  >
                    {[30, 60, 90, 180, 365].map((days) => (
                      <option key={days} value={days}>{days} days</option>
                    ))}
                  </select>
                </>
              }
              tone="warning"
              items={stock?.expiring}
            />
          </div>

          <div className="report-two-column">
            <StockList title="Low stock" hint="Time to reorder" tone="info" items={stock?.low} showExpiry={false} />

            <section className="dashboard-card report-card">
              <div className="card-header">
                <div>
                  <h2>Stock by category</h2>
                  <p>Value at MRP</p>
                </div>
              </div>

              {!stock || stock.categories.length === 0 ? (
                <div className="report-empty">No stock yet.</div>
              ) : (
                <div className="category-report-list">
                  {stock.categories.slice(0, 8).map((category) => (
                    <div className="category-row" key={category.name}>
                      <div>
                        <strong>{category.name}</strong>
                        <span>{category.units} units · {inr(category.value)}</span>
                      </div>
                      <div className="progress-track">
                        <div
                          className="progress-fill"
                          style={{ width: `${Math.max(5, (category.value / (stock.categories[0].value || 1)) * 100)}%` }}
                        />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </section>
          </div>

          <section className="dashboard-card report-card">
            <div className="card-header">
              <div>
                <h2>Download</h2>
                <p>Opens in Excel</p>
              </div>
            </div>
            <div className="rep-downloads">
              {exportButton("inventory", "Full inventory")}
              {exportButton("expiry", "Expiry list")}
            </div>
          </section>
        </>
      )}
    </div>
  );
}

function StockList({ title, hint, tone, items, showExpiry = true }) {
  const list = items || [];

  return (
    <section className="dashboard-card report-card">
      <div className="card-header">
        <div>
          <h2>
            {title} <span className={`report-pill ${tone}`}>{list.length}</span>
          </h2>
          <p>{hint}</p>
        </div>
      </div>

      {list.length === 0 ? (
        <div className="success-report">✓ Nothing here.</div>
      ) : (
        <div className="rep-stock-list">
          {list.slice(0, 12).map((item) => (
            <div className="rep-stock-row" key={`${item.id}-${item.batch}`}>
              <div>
                <strong>{item.name}</strong>
                <span>
                  {item.id} · Batch {item.batch || "-"}
                  {showExpiry ? ` · Exp ${formatDate(item.expiry)}` : ""}
                </span>
              </div>
              <div className="rep-stock-qty">
                <strong>{item.quantity}</strong>
                <span>units</span>
              </div>
            </div>
          ))}
          {list.length > 12 && (
            <p className="rep-note">Showing 12 of {list.length}. Download the list for all of them.</p>
          )}
        </div>
      )}
    </section>
  );
}

export default Reports;
