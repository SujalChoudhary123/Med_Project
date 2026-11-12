import { useCallback, useEffect, useState } from "react";
import "./customers.css";
import Dialog from "../layout/Dialog.jsx";
import { api } from "../../config.js";
import { DocumentModal, SellerHeader } from "../sales/InvoiceView.jsx";
import { formatInvoiceDate, inr, modeLabel, whatsappLink } from "../../utils/invoiceUtils.js";
import { downloadFile } from "../../utils/download.js";

async function readJson(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok || data.success === false) {
    throw new Error(data.error || "Something went wrong. Please try again.");
  }
  return data;
}

const KIND_LABELS = {
  sale: "Bill on udhaar",
  payment: "Payment received",
  return: "Return adjusted",
  cancel: "Bill cancelled",
};

/* ---------------- add / edit ---------------- */

function CustomerForm({ customer, isOwner, onClose, onSaved }) {
  const editing = Boolean(customer);
  const [form, setForm] = useState({
    name: customer?.name || "",
    phone: customer?.phone || "",
    gstin: customer?.gstin || "",
    address: customer?.address || "",
    notes: customer?.notes || "",
    creditLimit: customer ? String(customer.creditLimit || 0) : "0",
  });
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  const set = (key) => (event) => setForm({ ...form, [key]: event.target.value });

  const submit = async (event) => {
    event.preventDefault();
    setError("");

    try {
      setSaving(true);
      const body = { ...form, creditLimit: isOwner ? Number(form.creditLimit) || 0 : undefined };
      const data = await fetch(api(editing ? `/api/customers/${customer.id}` : "/api/customers"), {
        method: editing ? "PUT" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      }).then(readJson);
      onSaved(data.message, data.id);
    } catch (problem) {
      setError(problem.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog title={editing ? "Edit customer" : "Add customer"} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="return-body cust-form">
          <label>
            Name
            <input value={form.name} maxLength={80} autoFocus onChange={set("name")} />
          </label>
          <div className="cust-two">
            <label>
              Phone
              <input value={form.phone} maxLength={20} inputMode="tel" onChange={set("phone")} />
            </label>
            <label>
              GSTIN (optional)
              <input
                value={form.gstin}
                maxLength={15}
                onChange={(event) => setForm({ ...form, gstin: event.target.value.toUpperCase() })}
              />
            </label>
          </div>
          <label>
            Address (optional)
            <input value={form.address} maxLength={200} onChange={set("address")} />
          </label>
          <label>
            Notes (optional)
            <input value={form.notes} maxLength={300} onChange={set("notes")} />
          </label>
          {isOwner ? (
            <label>
              Credit limit ₹ (0 = no limit)
              <input
                type="number"
                min="0"
                step="1"
                inputMode="decimal"
                value={form.creditLimit}
                onChange={set("creditLimit")}
              />
            </label>
          ) : (
            <p className="cust-hint">Only the owner can set a credit limit.</p>
          )}
          {error && <div className="bill-message error" role="alert">{error}</div>}
        </div>
        <div className="return-foot">
          <span />
          <div className="return-buttons">
            <button type="button" className="secondary-button" onClick={onClose}>Cancel</button>
            <button type="submit" className="primary-button" disabled={saving}>
              {saving ? "Saving..." : "Save"}
            </button>
          </div>
        </div>
      </form>
    </Dialog>
  );
}

/* ---------------- receive payment ---------------- */

function PaymentDialog({ customer, onClose, onSaved }) {
  const owed = Math.max(0, Number(customer.balance) || 0);
  const [amount, setAmount] = useState(owed ? String(owed) : "");
  const [mode, setMode] = useState("cash");
  const [note, setNote] = useState("");
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  const submit = async (event) => {
    event.preventDefault();
    setError("");

    const value = Number(amount);
    if (!(value > 0)) {
      setError("Enter the amount received.");
      return;
    }

    try {
      setSaving(true);
      const data = await fetch(api(`/api/customers/${customer.id}/payments`), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ amount: value, mode, note }),
      }).then(readJson);
      onSaved(`${inr(value)} received from ${customer.name}. Balance now ${inr(data.balance)}.`);
    } catch (problem) {
      setError(problem.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog
      title="Receive payment"
      subtitle={`${customer.name} owes ${inr(owed)}`}
      onClose={onClose}
    >
      <form onSubmit={submit}>
        <div className="return-body cust-form">
          <div className="cust-two">
            <label>
              Amount received ₹
              <input
                type="number"
                min="0.01"
                step="0.01"
                inputMode="decimal"
                autoFocus
                value={amount}
                onChange={(event) => setAmount(event.target.value)}
              />
            </label>
            <label>
              Paid by
              <select value={mode} onChange={(event) => setMode(event.target.value)}>
                <option value="cash">Cash</option>
                <option value="upi">UPI</option>
                <option value="card">Card</option>
              </select>
            </label>
          </div>
          <label>
            Note (optional)
            <input value={note} maxLength={200} onChange={(event) => setNote(event.target.value)} />
          </label>
          {error && <div className="bill-message error" role="alert">{error}</div>}
        </div>
        <div className="return-foot">
          <span />
          <div className="return-buttons">
            <button type="button" className="secondary-button" onClick={onClose}>Cancel</button>
            <button type="submit" className="primary-button" disabled={saving}>
              {saving ? "Saving..." : "Save payment"}
            </button>
          </div>
        </div>
      </form>
    </Dialog>
  );
}

/* ---------------- printable statement ---------------- */

function Statement({ detail, onClose }) {
  const [shop, setShop] = useState(null);
  const { customer, ledger } = detail;

  useEffect(() => {
    fetch(api("/api/shop"))
      .then(readJson)
      .then(setShop)
      .catch(() => setShop({ name: "" }));
  }, []);

  const rows = [...ledger].reverse();
  const today = new Date().toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" });

  return (
    <DocumentModal label={`Statement - ${customer.name}`} status="Account statement" onClose={onClose}>
      <div className="invoice-sheet statement-sheet">
        <SellerHeader
          seller={{
            name: shop?.name || "",
            address: shop?.address || "",
            phone: shop?.phone || "",
            state: shop?.state || "",
            gstin: shop?.gstin || "",
            license: shop?.drugLicense || "",
          }}
          title="ACCOUNT STATEMENT"
          note={`As on ${today}`}
        />

        <section className="invoice-meta">
          <div>
            <span>Customer</span>
            <strong>{customer.name}</strong>
            {customer.phone && (<><span>Phone</span><strong>{customer.phone}</strong></>)}
            {customer.address && (<><span>Address</span><strong>{customer.address}</strong></>)}
          </div>
          <div>
            <span>Amount due</span>
            <strong>{inr(Math.max(0, customer.balance))}</strong>
            {customer.creditLimit > 0 && (<><span>Credit limit</span><strong>{inr(customer.creditLimit)}</strong></>)}
          </div>
        </section>

        <table className="invoice-table statement-table">
          <thead>
            <tr>
              <th className="left">Date</th>
              <th className="left">Details</th>
              <th>Debit</th>
              <th>Credit</th>
              <th>Balance</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id}>
                <td className="left">{formatInvoiceDate(row.date)}</td>
                <td className="left">
                  {KIND_LABELS[row.kind] || row.kind}
                  {row.invoiceNo ? ` · ${row.invoiceNo}` : ""}
                  {row.creditNoteNo ? ` · ${row.creditNoteNo}` : ""}
                </td>
                <td>{row.amount > 0 ? inr(row.amount) : ""}</td>
                <td>{row.amount < 0 ? inr(-row.amount) : ""}</td>
                <td>{inr(row.balanceAfter)}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={5} className="left">No entries yet.</td></tr>
            )}
          </tbody>
        </table>

        <p className="statement-foot">
          Debit = goods given on udhaar. Credit = money received or goods returned.
        </p>
      </div>
    </DocumentModal>
  );
}

/* ---------------- one customer ---------------- */

function CustomerDetail({ id, role, onClose, onChanged, onMessage }) {
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState("");
  const [dialog, setDialog] = useState("");
  const isOwner = role === "owner";

  const load = useCallback(async () => {
    try {
      setDetail(await fetch(api(`/api/customers/${id}`)).then(readJson));
    } catch (problem) {
      setError(problem.message);
    }
  }, [id]);

  useEffect(() => {
    const timer = setTimeout(load, 0);
    return () => clearTimeout(timer);
  }, [load]);

  const refresh = async (message) => {
    setDialog("");
    if (message) onMessage("success", message);
    await load();
    onChanged();
  };

  const remove = async () => {
    if (!window.confirm(`Delete ${detail.customer.name}? This cannot be undone.`)) return;
    try {
      await fetch(api(`/api/customers/${id}`), { method: "DELETE" }).then(readJson);
      onMessage("success", "Customer deleted.");
      onChanged();
      onClose();
    } catch (problem) {
      onMessage("error", problem.message);
    }
  };

  const remind = () => {
    const { customer } = detail;
    const text = `Namaste ${customer.name}, ${inr(customer.balance)} aapka baaki hai. Kripya jald bhugtan kar dein. Dhanyavaad.`;
    window.open(whatsappLink(customer.phone, text), "_blank", "noopener");
  };

  if (error) {
    return (
      <Dialog title="Customer" onClose={onClose}>
        <div className="return-body"><div className="bill-message error">{error}</div></div>
      </Dialog>
    );
  }

  if (!detail) return null;

  const { customer, ledger, invoices } = detail;
  const owes = customer.balance > 0;

  return (
    <>
      {dialog !== "statement" && (
      <Dialog
        title={customer.name}
        subtitle={[customer.phone, customer.gstin].filter(Boolean).join(" · ") || "No phone saved"}
        onClose={onClose}
      >
        <div className="return-body cust-detail">
          <div className="cust-balance">
            <div>
              <span>{owes ? "Amount due" : customer.balance < 0 ? "Advance with us" : "No dues"}</span>
              <strong className={owes ? "bad" : ""}>{inr(Math.abs(customer.balance))}</strong>
              {customer.creditLimit > 0 && <small>Credit limit {inr(customer.creditLimit)}</small>}
            </div>
            <div className="cust-actions">
              <button type="button" className="primary-button" onClick={() => setDialog("pay")}>
                Receive payment
              </button>
              {owes && customer.phone && (
                <button type="button" className="secondary-button" onClick={remind}>
                  WhatsApp reminder
                </button>
              )}
              <button type="button" className="secondary-button" onClick={() => setDialog("statement")}>
                Statement
              </button>
              <button type="button" className="secondary-button" onClick={() => setDialog("edit")}>
                Edit
              </button>
              {isOwner && (
                <button type="button" className="delete-button" onClick={remove}>
                  Delete
                </button>
              )}
            </div>
          </div>

          {(customer.address || customer.notes) && (
            <p className="cust-hint">
              {[customer.address, customer.notes].filter(Boolean).join(" · ")}
            </p>
          )}

          <h3 className="cust-sub">Account history</h3>
          {ledger.length === 0 ? (
            <div className="bill-empty small">Nothing yet. Bills on udhaar and payments show up here.</div>
          ) : (
            <div className="bill-table-wrap">
              <table className="bill-table cards">
                <thead>
                  <tr>
                    <th className="left">What</th>
                    <th className="right">Amount</th>
                    <th className="right">Balance</th>
                  </tr>
                </thead>
                <tbody>
                  {ledger.map((row) => (
                    <tr key={row.id}>
                      <td className="left lead">
                        <strong>{KIND_LABELS[row.kind] || row.kind}</strong>
                        <small>
                          {formatInvoiceDate(row.date)}
                          {row.invoiceNo ? ` · ${row.invoiceNo}` : ""}
                          {row.creditNoteNo ? ` · ${row.creditNoteNo}` : ""}
                          {row.kind === "payment" && row.mode ? ` · ${modeLabel(row.mode)}` : ""}
                        </small>
                      </td>
                      <td className={`right strong ${row.amount > 0 ? "bad" : "good"}`} data-label="Amount">
                        {row.amount > 0 ? "+" : "-"}{inr(Math.abs(row.amount))}
                      </td>
                      <td className="right" data-label="Balance">{inr(row.balanceAfter)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {invoices.length > 0 && (
            <>
              <h3 className="cust-sub">Recent bills</h3>
              <ul className="cust-bills">
                {invoices.map((invoice) => (
                  <li key={invoice.id}>
                    <span>
                      <strong>{invoice.invoiceNo}</strong>
                      <small>
                        {formatInvoiceDate(invoice.date)} · {modeLabel(invoice.paymentMode)}
                      </small>
                    </span>
                    <span>
                      <strong>{inr(invoice.total)}</strong>
                      {invoice.status === "cancelled" && <small>Cancelled</small>}
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      </Dialog>
      )}

      {dialog === "pay" && (
        <PaymentDialog customer={customer} onClose={() => setDialog("")} onSaved={refresh} />
      )}
      {dialog === "edit" && (
        <CustomerForm customer={customer} isOwner={isOwner} onClose={() => setDialog("")} onSaved={() => refresh("Customer updated.")} />
      )}
      {dialog === "statement" && <Statement detail={detail} onClose={() => setDialog("")} />}
    </>
  );
}

/* ---------------- the page ---------------- */

function Customers() {
  const role = localStorage.getItem("medtrackRole") || "cashier";

  const [data, setData] = useState({ summary: { customers: 0, withDues: 0, totalDues: 0 }, customers: [] });
  const [search, setSearch] = useState("");
  const [duesOnly, setDuesOnly] = useState(false);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState({ type: "", text: "" });
  const [openId, setOpenId] = useState(null);
  const [adding, setAdding] = useState(false);

  const load = useCallback(async () => {
    try {
      const result = await fetch(
        api(`/api/customers?limit=300&dues=${duesOnly ? 1 : 0}&search=${encodeURIComponent(search.trim())}`)
      ).then(readJson);
      setData(result);
    } catch (problem) {
      setMessage({ type: "error", text: problem.message });
    } finally {
      setLoading(false);
    }
  }, [search, duesOnly]);

  useEffect(() => {
    const timer = setTimeout(load, 220);
    return () => clearTimeout(timer);
  }, [load]);

  const flash = (type, text) => setMessage({ type, text });
  const canExport = role === "owner" || role === "pharmacist";

  const exportDues = async () => {
    try {
      await downloadFile(api("/api/reports/export?type=dues"), "udhaar-dues.csv");
    } catch (problem) {
      flash("error", problem.message || "The file could not be downloaded.");
    }
  };

  const { summary, customers } = data;

  return (
    <div className="bill-page cust-page">
      {message.text && (
        <div className={`bill-message ${message.type}`} role="status">
          {message.text}
          <button type="button" aria-label="Dismiss" onClick={() => setMessage({ type: "", text: "" })}>×</button>
        </div>
      )}

      <section className="cust-stats">
        <div className="stat-card">
          <div>
            <span>Total udhaar to collect</span>
            <strong className={summary.totalDues > 0 ? "bad" : ""}>{inr(summary.totalDues)}</strong>
            <small>Across all customers</small>
          </div>
        </div>
        <div className="stat-card">
          <div>
            <span>Customers with dues</span>
            <strong>{summary.withDues}</strong>
            <small>Waiting to pay</small>
          </div>
        </div>
        <div className="stat-card">
          <div>
            <span>Saved customers</span>
            <strong>{summary.customers}</strong>
            <small>In your book</small>
          </div>
        </div>
      </section>

      <section className="dashboard-card">
        <div className="card-header">
          <div>
            <h2>Customers &amp; udhaar</h2>
            <p>Tap a customer to see their account, receive money or send a reminder.</p>
          </div>
          <div className="cust-toolbar-buttons">
            {canExport && (
              <button type="button" className="secondary-button" onClick={exportDues}>
                Download dues (CSV)
              </button>
            )}
            <button type="button" className="primary-button" onClick={() => setAdding(true)}>
              + Add customer
            </button>
          </div>
        </div>

        <div className="cust-toolbar">
          <input
            type="search"
            className="bill-history-search"
            value={search}
            placeholder="Search name or phone"
            onChange={(event) => setSearch(event.target.value)}
          />
          <label className="cust-toggle">
            <input type="checkbox" checked={duesOnly} onChange={(event) => setDuesOnly(event.target.checked)} />
            Only customers who owe
          </label>
        </div>

        {loading ? (
          <div className="bill-empty small">Loading customers...</div>
        ) : customers.length === 0 ? (
          <div className="bill-empty small">
            {search || duesOnly
              ? "No customer matches."
              : "No customers yet. Add one, then sell to them on udhaar from the billing page."}
          </div>
        ) : (
          <div className="bill-table-wrap">
            <table className="bill-table history cards">
              <thead>
                <tr>
                  <th className="left">Customer</th>
                  <th className="left">Phone</th>
                  <th className="right">Owes</th>
                  <th className="right">Limit</th>
                  <th className="left">Last activity</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {customers.map((customer) => (
                  <tr key={customer.id}>
                    <td className="left strong lead">{customer.name}</td>
                    <td className="left" data-label="Phone">{customer.phone || "-"}</td>
                    <td className={`right strong ${customer.balance > 0 ? "bad" : ""}`} data-label="Owes">
                      {customer.balance > 0 ? inr(customer.balance) : customer.balance < 0 ? `Advance ${inr(-customer.balance)}` : "-"}
                    </td>
                    <td className="right" data-label="Limit">{customer.creditLimit > 0 ? inr(customer.creditLimit) : "No limit"}</td>
                    <td className="left" data-label="Last activity">
                      {customer.lastActivity ? formatInvoiceDate(customer.lastActivity) : "-"}
                    </td>
                    <td className="right action">
                      <button type="button" className="secondary-button" onClick={() => setOpenId(customer.id)}>
                        Open
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {adding && (
        <CustomerForm
          isOwner={role === "owner"}
          onClose={() => setAdding(false)}
          onSaved={(text, id) => {
            setAdding(false);
            flash("success", text || "Customer added.");
            load();
            if (id) setOpenId(id);
          }}
        />
      )}

      {openId && (
        <CustomerDetail
          id={openId}
          role={role}
          onClose={() => setOpenId(null)}
          onChanged={load}
          onMessage={flash}
        />
      )}
    </div>
  );
}

export default Customers;
