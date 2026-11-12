import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import "./purchases.css";
import Dialog from "../layout/Dialog.jsx";
import Icon from "../layout/Icons.jsx";
import { api } from "../../config.js";
import { formatInvoiceDate, inr, modeLabel, r2 } from "../../utils/invoiceUtils.js";
import { friendlyError, jsonPost, newRef, readJson, todayIso } from "../../utils/apiHelpers.js";

const GST_RATES = [0, 5, 12, 18, 28];
const PAY_MODES = [
  ["cash", "Cash"],
  ["upi", "UPI"],
  ["bank", "Bank transfer"],
  ["card", "Card"],
];
const KIND_LABELS = {
  purchase: "Purchase",
  payment: "Payment made",
  cancel: "Purchase cancelled",
};

const lineAmounts = (line) => {
  const taxable = r2((Number(line.cost) || 0) * (Number(line.quantity) || 0));
  const gst = r2((taxable * (Number(line.gstRate) || 0)) / 100);
  return { taxable, gst, total: r2(taxable + gst) };
};

/* ---------------- supplier form ---------------- */

function SupplierForm({ supplier, onClose, onSaved }) {
  const editing = Boolean(supplier);
  const [form, setForm] = useState({
    name: supplier?.name || "",
    phone: supplier?.phone || "",
    gstin: supplier?.gstin || "",
    drugLicense: supplier?.drugLicense || "",
    address: supplier?.address || "",
    notes: supplier?.notes || "",
  });
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  const set = (key) => (event) => setForm({ ...form, [key]: event.target.value });

  const submit = async (event) => {
    event.preventDefault();
    setError("");

    try {
      setSaving(true);
      const data = await fetch(
        api(editing ? `/api/suppliers/${supplier.id}` : "/api/suppliers"),
        jsonPost(form, editing ? "PUT" : "POST")
      ).then(readJson);
      onSaved(data.id, form);
    } catch (problem) {
      setError(friendlyError(problem));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog title={editing ? "Edit supplier" : "Add supplier"} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="return-body cust-form">
          <label>
            Supplier name
            <input value={form.name} maxLength={100} autoFocus onChange={set("name")} />
          </label>
          <div className="cust-two">
            <label>
              Phone
              <input value={form.phone} maxLength={20} inputMode="tel" onChange={set("phone")} />
            </label>
            <label>
              GSTIN
              <input
                value={form.gstin}
                maxLength={15}
                onChange={(event) => setForm({ ...form, gstin: event.target.value.toUpperCase() })}
              />
            </label>
          </div>
          <label>
            Drug licence (optional)
            <input value={form.drugLicense} maxLength={60} onChange={set("drugLicense")} />
          </label>
          <label>
            Address (optional)
            <input value={form.address} maxLength={200} onChange={set("address")} />
          </label>
          <label>
            Notes (optional)
            <input value={form.notes} maxLength={300} onChange={set("notes")} />
          </label>
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

/* ---------------- new purchase ---------------- */

const blankLine = (medicine, typedName) => ({
  key: newRef(),
  medicineId: medicine?.id || "",
  name: medicine?.name || typedName || "",
  batch: "",
  expiry: "",
  quantity: 1,
  freeQty: 0,
  cost: medicine && medicine.cost > 0 ? medicine.cost : "",
  mrp: medicine?.mrp || "",
  gstRate: medicine ? Number(medicine.gstRate) : 5,
  hsn: medicine?.hsn || "3004",
});

function NewPurchase({ suppliers, onSupplierAdded, onSaved, flash }) {
  const [supplierId, setSupplierId] = useState("");
  const [billNo, setBillNo] = useState("");
  const [billDate, setBillDate] = useState(todayIso());
  const [lines, setLines] = useState([]);
  const [amountPaid, setAmountPaid] = useState("");
  const [paidMode, setPaidMode] = useState("cash");
  const [notes, setNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const [addingSupplier, setAddingSupplier] = useState(false);

  const [query, setQuery] = useState("");
  const [results, setResults] = useState([]);
  const [open, setOpen] = useState(false);
  const ticket = useRef(0);
  const clientRef = useRef(newRef());

  useEffect(() => {
    const text = query.trim();
    const mine = ++ticket.current;
    if (!text) return undefined;

    const timer = setTimeout(async () => {
      try {
        const data = await fetch(api(`/api/purchases/medicines?search=${encodeURIComponent(text)}`)).then(readJson);
        if (mine === ticket.current) setResults(Array.isArray(data) ? data : []);
      } catch {
        if (mine === ticket.current) setResults([]);
      }
    }, 220);

    return () => clearTimeout(timer);
  }, [query]);

  const addLine = (medicine) => {
    setLines((current) => [...current, blankLine(medicine, query.trim())]);
    setQuery("");
    setResults([]);
    setOpen(false);
  };

  const change = (key, changes) =>
    setLines((current) => current.map((line) => (line.key === key ? { ...line, ...changes } : line)));

  const remove = (key) => setLines((current) => current.filter((line) => line.key !== key));

  const totals = useMemo(() => {
    const sum = { taxable: 0, gst: 0, units: 0 };
    lines.forEach((line) => {
      const amounts = lineAmounts(line);
      sum.taxable += amounts.taxable;
      sum.gst += amounts.gst;
      sum.units += (Number(line.quantity) || 0) + (Number(line.freeQty) || 0);
    });
    return { taxable: r2(sum.taxable), gst: r2(sum.gst), total: r2(sum.taxable + sum.gst), units: sum.units };
  }, [lines]);

  const paid = Number(amountPaid) || 0;

  const problem = !supplierId
    ? "Choose a supplier."
    : lines.length === 0
      ? "Add at least one medicine."
      : lines.some((line) => !line.batch.trim())
        ? "Enter the batch number on every line."
        : lines.some((line) => !line.expiry)
          ? "Enter the expiry date on every line."
          : lines.some((line) => line.cost === "" || !(Number(line.mrp) > 0))
            ? "Enter the purchase rate and MRP on every line."
            : paid > totals.total
              ? "The amount paid is more than the total."
              : "";

  const save = async () => {
    if (problem) {
      flash("error", problem);
      return;
    }

    try {
      setSaving(true);

      const data = await fetch(
        api("/api/purchases"),
        jsonPost({
          supplierId: Number(supplierId),
          billNo: billNo.trim(),
          billDate,
          amountPaid: paid,
          paidMode,
          notes: notes.trim(),
          clientRef: clientRef.current,
          items: lines.map((line) => ({
            medicineId: line.medicineId,
            name: line.name.trim(),
            batch: line.batch.trim(),
            expiry: line.expiry,
            quantity: Number(line.quantity),
            freeQty: Number(line.freeQty) || 0,
            cost: Number(line.cost),
            mrp: Number(line.mrp),
            gstRate: Number(line.gstRate),
            hsn: String(line.hsn).trim(),
          })),
        })
      ).then(readJson);

      clientRef.current = newRef();
      setLines([]);
      setBillNo("");
      setNotes("");
      setAmountPaid("");
      flash("success", `${data.purchase.purchaseNo} saved. ${totals.units} units added to stock.`);
      onSaved();
    } catch (problemSaving) {
      flash("error", friendlyError(problemSaving));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="pur-grid">
      <section className="dashboard-card">
        <div className="card-header">
          <div>
            <h2>Stock in</h2>
            <p>Enter the supplier's bill. Stock, cost and dues update together.</p>
          </div>
        </div>

        <div className="pur-head">
          <label>
            Supplier
            <span className="pur-supplier">
              <select value={supplierId} onChange={(event) => setSupplierId(event.target.value)}>
                <option value="">Choose a supplier</option>
                {suppliers.map((supplier) => (
                  <option key={supplier.id} value={supplier.id}>{supplier.name}</option>
                ))}
              </select>
              <button type="button" className="secondary-button" onClick={() => setAddingSupplier(true)}>
                + New
              </button>
            </span>
          </label>
          <label>
            Supplier bill no.
            <input value={billNo} maxLength={40} placeholder="Printed on their bill" onChange={(event) => setBillNo(event.target.value)} />
          </label>
          <label>
            Bill date
            <input type="date" value={billDate} max={todayIso()} onChange={(event) => setBillDate(event.target.value)} />
          </label>
        </div>

        <div className="bill-search">
          <Icon name="pill" size={18} />
          <input
            type="search"
            value={query}
            placeholder="Search a medicine, or type a new name"
            autoComplete="off"
            onChange={(event) => {
              setQuery(event.target.value);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
          />
          {open && query.trim() && (
            <ul className="bill-results">
              {results.map((medicine) => (
                <li key={medicine.id}>
                  <button type="button" onMouseDown={(event) => event.preventDefault()} onClick={() => addLine(medicine)}>
                    <span className="name">
                      <strong>{medicine.name}</strong>
                      <small>{medicine.id} · Batch {medicine.batch || "-"} · Exp {medicine.expiry || "-"}</small>
                    </span>
                    <span className="meta">
                      <strong>{inr(medicine.mrp)}</strong>
                      <small>{medicine.quantity} in stock</small>
                    </span>
                  </button>
                </li>
              ))}
              <li>
                <button type="button" onMouseDown={(event) => event.preventDefault()} onClick={() => addLine(null)}>
                  <span className="name">
                    <strong>+ Add "{query.trim()}" as a new medicine</strong>
                  </span>
                </button>
              </li>
            </ul>
          )}
        </div>

        {lines.length === 0 ? (
          <div className="bill-empty">
            <div className="empty-icon"><Icon name="plus" size={24} /></div>
            <h3>Nothing added yet</h3>
            <p>Search above and add each medicine from the supplier's bill.</p>
          </div>
        ) : (
          <div className="pur-lines">
            {lines.map((line) => {
              const amounts = lineAmounts(line);
              return (
                <div className="pur-line" key={line.key}>
                  <div className="pur-line-head">
                    <div>
                      <strong>{line.name}</strong>
                      <small>{line.medicineId ? "New stock of an existing medicine" : "New medicine"}</small>
                    </div>
                    <button type="button" className="bill-remove" aria-label={`Remove ${line.name}`} onClick={() => remove(line.key)}>×</button>
                  </div>
                  <div className="pur-fields">
                    <label>Batch<input value={line.batch} maxLength={40} onChange={(event) => change(line.key, { batch: event.target.value })} /></label>
                    <label>Expiry<input type="date" min={todayIso()} value={line.expiry} onChange={(event) => change(line.key, { expiry: event.target.value })} /></label>
                    <label>Qty<input type="number" min="1" value={line.quantity} onChange={(event) => change(line.key, { quantity: Math.max(0, parseInt(event.target.value, 10) || 0) })} /></label>
                    <label>Free<input type="number" min="0" value={line.freeQty} onChange={(event) => change(line.key, { freeQty: Math.max(0, parseInt(event.target.value, 10) || 0) })} /></label>
                    <label>Rate before GST<input type="number" min="0" step="0.01" inputMode="decimal" value={line.cost} onChange={(event) => change(line.key, { cost: event.target.value })} /></label>
                    <label>MRP<input type="number" min="0" step="0.01" inputMode="decimal" value={line.mrp} onChange={(event) => change(line.key, { mrp: event.target.value })} /></label>
                    <label>
                      GST
                      <select value={line.gstRate} onChange={(event) => change(line.key, { gstRate: Number(event.target.value) })}>
                        {GST_RATES.map((rate) => <option key={rate} value={rate}>{rate}%</option>)}
                      </select>
                    </label>
                    <label>Amount<output>{inr(amounts.total)}</output></label>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>

      <aside className="dashboard-card pur-summary">
        <div className="card-header">
          <div>
            <h2>Purchase summary</h2>
            <p>{lines.length} item{lines.length === 1 ? "" : "s"} · {totals.units} units with free</p>
          </div>
        </div>

        <dl className="bill-totals">
          <div><dt>Taxable value</dt><dd>{inr(totals.taxable)}</dd></div>
          <div><dt>Input GST</dt><dd>{inr(totals.gst)}</dd></div>
          <div className="grand"><dt>Total</dt><dd>{inr(totals.total)}</dd></div>
        </dl>

        <div className="cust-form pur-pay">
          <div className="cust-two">
            <label>
              Paid now ₹
              <input type="number" min="0" step="0.01" inputMode="decimal" value={amountPaid} placeholder="0" onChange={(event) => setAmountPaid(event.target.value)} />
            </label>
            <label>
              Paid by
              <select value={paidMode} onChange={(event) => setPaidMode(event.target.value)}>
                {PAY_MODES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </label>
          </div>
          <label>
            Notes (optional)
            <input value={notes} maxLength={300} onChange={(event) => setNotes(event.target.value)} />
          </label>
        </div>

        <p className={problem ? "pur-hint bad" : "pur-hint"}>
          {problem || `Goes on the supplier's account: ${inr(Math.max(0, totals.total - paid))}`}
        </p>

        <button type="button" className="primary-button bill-generate" disabled={saving || Boolean(problem)} onClick={save}>
          {saving ? "Saving..." : `Save purchase · ${inr(totals.total)}`}
        </button>
      </aside>

      {addingSupplier && (
        <SupplierForm
          onClose={() => setAddingSupplier(false)}
          onSaved={(id) => {
            setAddingSupplier(false);
            onSupplierAdded(id, setSupplierId);
          }}
        />
      )}
    </div>
  );
}

/* ---------------- purchase history ---------------- */

function History({ flash, reloadKey, canCancel, onChanged }) {
  const [search, setSearch] = useState("");
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(null);

  const load = useCallback(async () => {
    try {
      const data = await fetch(api(`/api/purchases?limit=100&search=${encodeURIComponent(search)}`)).then(readJson);
      setRows(Array.isArray(data) ? data : []);
    } catch (problem) {
      flash("error", friendlyError(problem));
    } finally {
      setLoading(false);
    }
  }, [search, flash]);

  useEffect(() => {
    const timer = setTimeout(load, 220);
    return () => clearTimeout(timer);
  }, [load, reloadKey]);

  const view = async (id) => {
    try {
      const data = await fetch(api(`/api/purchases/${id}`)).then(readJson);
      setOpen(data.purchase);
    } catch (problem) {
      flash("error", friendlyError(problem));
    }
  };

  const cancel = async () => {
    if (!window.confirm(`Cancel ${open.purchaseNo}? The stock goes back out and the supplier's account is corrected.`)) return;

    try {
      const data = await fetch(api(`/api/purchases/${open.id}/cancel`), { method: "POST" }).then(readJson);
      flash("success", data.message);
      setOpen(null);
      load();
      onChanged();
    } catch (problem) {
      flash("error", friendlyError(problem));
    }
  };

  return (
    <section className="dashboard-card">
      <div className="card-header">
        <div>
          <h2>Purchase history</h2>
          <p>Every delivery entered, newest first</p>
        </div>
        <input
          type="search"
          className="bill-history-search"
          value={search}
          placeholder="Search number, bill or supplier"
          onChange={(event) => setSearch(event.target.value)}
        />
      </div>

      {loading ? (
        <div className="bill-empty small">Loading...</div>
      ) : rows.length === 0 ? (
        <div className="bill-empty small">{search ? "Nothing matches." : "No purchases yet."}</div>
      ) : (
        <div className="bill-table-wrap">
          <table className="bill-table history cards">
            <thead>
              <tr>
                <th className="left">Number</th>
                <th className="left">Supplier</th>
                <th className="left">Bill no.</th>
                <th className="left">Bill date</th>
                <th>Items</th>
                <th className="right">Total</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id} className={row.status === "cancelled" ? "cancelled" : ""}>
                  <td className="left strong lead">{row.purchaseNo}</td>
                  <td className="left" data-label="Supplier">{row.supplierName}</td>
                  <td className="left" data-label="Bill no.">{row.billNo || "-"}</td>
                  <td className="left" data-label="Bill date">{row.billDate}</td>
                  <td data-label="Items">{row.itemCount}</td>
                  <td className="right strong" data-label="Total">{inr(row.total)}</td>
                  <td data-label="Status">
                    <span className={`bill-status ${row.status === "cancelled" ? "cancelled" : "active"}`}>
                      {row.status === "cancelled" ? "Cancelled" : "Received"}
                    </span>
                  </td>
                  <td className="right action">
                    <button type="button" className="secondary-button" onClick={() => view(row.id)}>View</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {open && (
        <Dialog title={open.purchaseNo} subtitle={`${open.supplierName} · bill ${open.billNo || "-"} · ${open.billDate}`} onClose={() => setOpen(null)} wide>
          <div className="return-body">
            <div className="bill-table-wrap">
              <table className="bill-table cards">
                <thead>
                  <tr>
                    <th className="left">Medicine</th>
                    <th>Qty + free</th>
                    <th className="right">Rate</th>
                    <th className="right">MRP</th>
                    <th>GST</th>
                    <th className="right">Amount</th>
                  </tr>
                </thead>
                <tbody>
                  {open.items.map((item) => (
                    <tr key={`${item.medicineId}-${item.batch}`}>
                      <td className="left lead">
                        <strong>{item.name}</strong>
                        <small>{item.medicineId} · Batch {item.batch} · Exp {item.expiry}</small>
                      </td>
                      <td data-label="Qty + free">{item.quantity}{item.freeQty ? ` + ${item.freeQty}` : ""}</td>
                      <td className="right" data-label="Rate">{inr(item.cost)}</td>
                      <td className="right" data-label="MRP">{inr(item.mrp)}</td>
                      <td data-label="GST">{item.gstRate}%</td>
                      <td className="right strong" data-label="Amount">{inr(item.lineTotal)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <dl className="bill-totals">
              <div><dt>Taxable value</dt><dd>{inr(open.taxable)}</dd></div>
              <div><dt>CGST</dt><dd>{inr(open.cgst)}</dd></div>
              <div><dt>SGST</dt><dd>{inr(open.sgst)}</dd></div>
              <div className="grand"><dt>Total</dt><dd>{inr(open.total)}</dd></div>
              <div><dt>Paid at entry</dt><dd>{inr(open.amountPaid)}</dd></div>
            </dl>
            {open.notes && <p className="cust-hint">{open.notes}</p>}
          </div>
          {canCancel && open.status === "active" && (
            <div className="return-foot">
              <span />
              <div className="return-buttons">
                <button type="button" className="delete-button" onClick={cancel}>Cancel this purchase</button>
              </div>
            </div>
          )}
        </Dialog>
      )}
    </section>
  );
}

/* ---------------- suppliers ---------------- */

function PayDialog({ supplier, onClose, onSaved }) {
  const owed = Math.max(0, Number(supplier.balance) || 0);
  const [amount, setAmount] = useState(owed ? String(owed) : "");
  const [mode, setMode] = useState("bank");
  const [note, setNote] = useState("");
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  const submit = async (event) => {
    event.preventDefault();
    setError("");

    if (!(Number(amount) > 0)) {
      setError("Enter the amount paid.");
      return;
    }

    try {
      setSaving(true);
      const data = await fetch(
        api(`/api/suppliers/${supplier.id}/payments`),
        jsonPost({ amount: Number(amount), mode, note })
      ).then(readJson);
      onSaved(`${inr(amount)} paid to ${supplier.name}. Still payable: ${inr(data.balance)}.`);
    } catch (problem) {
      setError(friendlyError(problem));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog title="Pay supplier" subtitle={`You owe ${supplier.name} ${inr(owed)}`} onClose={onClose}>
      <form onSubmit={submit}>
        <div className="return-body cust-form">
          <div className="cust-two">
            <label>
              Amount ₹
              <input type="number" min="0.01" step="0.01" inputMode="decimal" autoFocus value={amount} onChange={(event) => setAmount(event.target.value)} />
            </label>
            <label>
              Paid by
              <select value={mode} onChange={(event) => setMode(event.target.value)}>
                {PAY_MODES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </label>
          </div>
          <label>
            Note (optional)
            <input value={note} maxLength={200} placeholder="Cheque no., UTR..." onChange={(event) => setNote(event.target.value)} />
          </label>
          {error && <div className="bill-message error" role="alert">{error}</div>}
        </div>
        <div className="return-foot">
          <span />
          <div className="return-buttons">
            <button type="button" className="secondary-button" onClick={onClose}>Cancel</button>
            <button type="submit" className="primary-button" disabled={saving}>{saving ? "Saving..." : "Save payment"}</button>
          </div>
        </div>
      </form>
    </Dialog>
  );
}

function SupplierDetail({ id, isOwner, onClose, onChanged, flash }) {
  const [detail, setDetail] = useState(null);
  const [dialog, setDialog] = useState("");

  const load = useCallback(async () => {
    try {
      setDetail(await fetch(api(`/api/suppliers/${id}`)).then(readJson));
    } catch (problem) {
      flash("error", friendlyError(problem));
      onClose();
    }
  }, [id, flash, onClose]);

  useEffect(() => {
    const timer = setTimeout(load, 0);
    return () => clearTimeout(timer);
  }, [load]);

  const refresh = async (message) => {
    setDialog("");
    if (message) flash("success", message);
    await load();
    onChanged();
  };

  const remove = async () => {
    if (!window.confirm(`Delete ${detail.supplier.name}? This cannot be undone.`)) return;
    try {
      await fetch(api(`/api/suppliers/${id}`), { method: "DELETE" }).then(readJson);
      flash("success", "Supplier deleted.");
      onChanged();
      onClose();
    } catch (problem) {
      flash("error", friendlyError(problem));
    }
  };

  if (!detail) return null;

  const { supplier, ledger } = detail;
  const owes = supplier.balance > 0;

  return (
    <>
      <Dialog
        title={supplier.name}
        subtitle={[supplier.phone, supplier.gstin].filter(Boolean).join(" · ") || "No phone saved"}
        onClose={onClose}
      >
        <div className="return-body cust-detail">
          <div className="cust-balance">
            <div>
              <span>{owes ? "You owe" : supplier.balance < 0 ? "Advance paid" : "Nothing due"}</span>
              <strong className={owes ? "bad" : ""}>{inr(Math.abs(supplier.balance))}</strong>
            </div>
            <div className="cust-actions">
              <button type="button" className="primary-button" onClick={() => setDialog("pay")}>Pay supplier</button>
              <button type="button" className="secondary-button" onClick={() => setDialog("edit")}>Edit</button>
              {isOwner && <button type="button" className="delete-button" onClick={remove}>Delete</button>}
            </div>
          </div>

          {(supplier.address || supplier.notes || supplier.drugLicense) && (
            <p className="cust-hint">
              {[supplier.drugLicense && `DL ${supplier.drugLicense}`, supplier.address, supplier.notes].filter(Boolean).join(" · ")}
            </p>
          )}

          <h3 className="cust-sub">Account history</h3>
          {ledger.length === 0 ? (
            <div className="bill-empty small">Nothing yet. Purchases and payments show up here.</div>
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
                          {row.purchaseNo ? ` · ${row.purchaseNo}` : ""}
                          {row.billNo ? ` · bill ${row.billNo}` : ""}
                          {row.kind === "payment" && row.mode ? ` · ${modeLabel(row.mode)}` : ""}
                          {row.note && !row.purchaseNo ? ` · ${row.note}` : ""}
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
        </div>
      </Dialog>

      {dialog === "pay" && <PayDialog supplier={supplier} onClose={() => setDialog("")} onSaved={refresh} />}
      {dialog === "edit" && (
        <SupplierForm supplier={supplier} onClose={() => setDialog("")} onSaved={() => refresh("Supplier updated.")} />
      )}
    </>
  );
}

function Suppliers({ data, search, setSearch, duesOnly, setDuesOnly, onAdd, onOpen }) {
  const { summary, suppliers } = data;

  return (
    <>
      <section className="cust-stats">
        <div className="stat-card">
          <div>
            <span>Total you owe</span>
            <strong className={summary.totalDues > 0 ? "bad" : ""}>{inr(summary.totalDues)}</strong>
            <small>To all suppliers</small>
          </div>
        </div>
        <div className="stat-card">
          <div>
            <span>Suppliers to pay</span>
            <strong>{summary.withDues}</strong>
            <small>With a balance</small>
          </div>
        </div>
        <div className="stat-card">
          <div>
            <span>Saved suppliers</span>
            <strong>{summary.suppliers}</strong>
            <small>In your book</small>
          </div>
        </div>
      </section>

      <section className="dashboard-card">
        <div className="card-header">
          <div>
            <h2>Suppliers</h2>
            <p>Open a supplier to see the account or record a payment.</p>
          </div>
          <button type="button" className="primary-button" onClick={onAdd}>+ Add supplier</button>
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
            Only suppliers I owe
          </label>
        </div>

        {suppliers.length === 0 ? (
          <div className="bill-empty small">{search || duesOnly ? "Nothing matches." : "No suppliers yet."}</div>
        ) : (
          <div className="bill-table-wrap">
            <table className="bill-table history cards">
              <thead>
                <tr>
                  <th className="left">Supplier</th>
                  <th className="left">Phone</th>
                  <th className="left">GSTIN</th>
                  <th className="right">You owe</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {suppliers.map((supplier) => (
                  <tr key={supplier.id}>
                    <td className="left strong lead">{supplier.name}</td>
                    <td className="left" data-label="Phone">{supplier.phone || "-"}</td>
                    <td className="left" data-label="GSTIN">{supplier.gstin || "-"}</td>
                    <td className={`right strong ${supplier.balance > 0 ? "bad" : ""}`} data-label="You owe">
                      {supplier.balance > 0 ? inr(supplier.balance) : supplier.balance < 0 ? `Advance ${inr(-supplier.balance)}` : "-"}
                    </td>
                    <td className="right action">
                      <button type="button" className="secondary-button" onClick={() => onOpen(supplier.id)}>Open</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

/* ---------------- the page ---------------- */

function Purchases() {
  const role = localStorage.getItem("medtrackRole") || "cashier";

  const [tab, setTab] = useState("new");
  const [message, setMessage] = useState({ type: "", text: "" });
  const [reloadKey, setReloadKey] = useState(0);

  const [data, setData] = useState({ summary: { suppliers: 0, withDues: 0, totalDues: 0 }, suppliers: [] });
  const [everyone, setEveryone] = useState([]);
  const [search, setSearch] = useState("");
  const [duesOnly, setDuesOnly] = useState(false);
  const [openId, setOpenId] = useState(null);
  const [adding, setAdding] = useState(false);

  const flash = useCallback((type, text) => setMessage({ type, text }), []);
  const closeDetail = useCallback(() => setOpenId(null), []);

  const load = useCallback(async () => {
    try {
      const [list, all] = await Promise.all([
        fetch(api(`/api/suppliers?limit=300&dues=${duesOnly ? 1 : 0}&search=${encodeURIComponent(search.trim())}`)).then(readJson),
        fetch(api("/api/suppliers?limit=500")).then(readJson),
      ]);
      setData(list);
      setEveryone([...all.suppliers].sort((a, b) => a.name.localeCompare(b.name)));
    } catch (problem) {
      flash("error", friendlyError(problem));
    }
  }, [search, duesOnly, flash]);

  useEffect(() => {
    const timer = setTimeout(load, 220);
    return () => clearTimeout(timer);
  }, [load, reloadKey]);

  const changed = useCallback(() => setReloadKey((key) => key + 1), []);

  return (
    <div className="bill-page cust-page">
      {message.text && (
        <div className={`bill-message ${message.type}`} role="status">
          {message.text}
          <button type="button" aria-label="Dismiss" onClick={() => setMessage({ type: "", text: "" })}>×</button>
        </div>
      )}

      <div className="rep-tabs" role="tablist">
        {[
          ["new", "New purchase"],
          ["history", "Purchase history"],
          ["suppliers", "Suppliers"],
        ].map(([key, label]) => (
          <button key={key} type="button" role="tab" aria-selected={tab === key} className={tab === key ? "active" : ""} onClick={() => setTab(key)}>
            {label}
          </button>
        ))}
      </div>

      {tab === "new" && (
        <NewPurchase
          suppliers={everyone}
          flash={flash}
          onSaved={changed}
          onSupplierAdded={(id, select) => {
            changed();
            select(String(id));
          }}
        />
      )}

      {tab === "history" && (
        <History flash={flash} reloadKey={reloadKey} canCancel onChanged={changed} />
      )}

      {tab === "suppliers" && (
        <Suppliers
          data={data}
          search={search}
          setSearch={setSearch}
          duesOnly={duesOnly}
          setDuesOnly={setDuesOnly}
          onAdd={() => setAdding(true)}
          onOpen={setOpenId}
        />
      )}

      {adding && (
        <SupplierForm
          onClose={() => setAdding(false)}
          onSaved={(id) => {
            setAdding(false);
            changed();
            setOpenId(id);
          }}
        />
      )}

      {openId && (
        <SupplierDetail
          id={openId}
          isOwner={role === "owner"}
          onClose={closeDetail}
          onChanged={changed}
          flash={flash}
        />
      )}
    </div>
  );
}

export default Purchases;
