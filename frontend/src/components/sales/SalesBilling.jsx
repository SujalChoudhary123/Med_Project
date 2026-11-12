import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import "./billing.css";
import Icon from "../layout/Icons.jsx";
import { CreditNoteModal, InvoiceModal } from "./InvoiceView.jsx";
import ReturnDialog from "./ReturnDialog.jsx";
import CustomerPicker from "./CustomerPicker.jsx";
import { api } from "../../config.js";
import { friendlyError, newRef } from "../../utils/apiHelpers.js";
import {
  PAYMENT_MODES,
  computeLine,
  formatInvoiceDate,
  inr,
  modeLabel,
  r2,
} from "../../utils/invoiceUtils.js";

const todayText = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(
    now.getDate()
  ).padStart(2, "0")}`;
};

const EMPTY_CUSTOMER = { name: "", phone: "", gstin: "" };

async function readJson(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok || data.success === false) {
    throw new Error(data.error || "Something went wrong. Please try again.");
  }
  return data;
}

function SalesBilling() {
  const role = localStorage.getItem("medtrackRole") || "cashier";
  const canCancel = role === "owner" || role === "pharmacist";

  const [shop, setShop] = useState(null);
  const inclusive = shop ? shop.pricesIncludeGst !== false : true;

  // ----- bill being built -----
  const [query, setQuery] = useState("");
  const [results, setResults] = useState([]);
  const [searching, setSearching] = useState(false);
  const [showResults, setShowResults] = useState(false);
  const [cart, setCart] = useState([]);
  const [customer, setCustomer] = useState(EMPTY_CUSTOMER);
  const [paymentMode, setPaymentMode] = useState("cash");
  const [picked, setPicked] = useState(null);
  const [amountPaid, setAmountPaid] = useState("");
  const [paidMode, setPaidMode] = useState("cash");
  const [saving, setSaving] = useState(false);

  // ----- history -----
  const [invoices, setInvoices] = useState([]);
  const [historySearch, setHistorySearch] = useState("");
  const [historyLoading, setHistoryLoading] = useState(true);

  // ----- returns (credit notes) -----
  const [notes, setNotes] = useState([]);
  const [returnFor, setReturnFor] = useState(null);
  const [openNote, setOpenNote] = useState(null);

  const [message, setMessage] = useState({ type: "", text: "" });
  const [openInvoice, setOpenInvoice] = useState(null);

  const searchBox = useRef(null);
  const searchCounter = useRef(0);
  // Stays the same until the bill is saved, so pressing the button again after a
  // dropped connection cannot create a second bill.
  const clientRef = useRef(newRef());

  const flash = (type, text) => setMessage({ type, text });

  /* ---------------- shop (tax mode) ---------------- */

  useEffect(() => {
    fetch(api("/api/shop"))
      .then(readJson)
      .then(setShop)
      .catch(() => setShop(null));
  }, []);

  /* ---------------- medicine search ---------------- */

  useEffect(() => {
    const text = query.trim();
    const ticket = ++searchCounter.current;

    if (!text) {
      return undefined;
    }

    const timer = setTimeout(async () => {
      try {
        setSearching(true);

        const byName = await fetch(
          api(`/api/medicines?search=${encodeURIComponent(text)}&filter=Name`)
        ).then(readJson);

        let found = Array.isArray(byName) ? byName : [];

        // Scanner / typed ID: fall back to an ID search when no name matches.
        if (found.length === 0) {
          const byId = await fetch(
            api(`/api/medicines?search=${encodeURIComponent(text)}&filter=${encodeURIComponent("Medicine ID")}`)
          ).then(readJson);
          found = Array.isArray(byId) ? byId : [];
        }

        if (ticket === searchCounter.current) {
          setResults(found.slice(0, 8));
          setSearching(false);
        }
      } catch (error) {
        if (ticket === searchCounter.current) {
          setResults([]);
          setSearching(false);
          flash("error", error.message);
        }
      }
    }, 220);

    return () => clearTimeout(timer);
  }, [query]);

  /* ---------------- history ---------------- */

  const loadInvoices = useCallback(async (search = "") => {
    try {
      setHistoryLoading(true);
      const data = await fetch(
        api(`/api/invoices?limit=100&search=${encodeURIComponent(search)}`)
      ).then(readJson);
      setInvoices(Array.isArray(data) ? data : []);

      const noteData = await fetch(
        api(`/api/credit-notes?limit=100&search=${encodeURIComponent(search)}`)
      ).then(readJson);
      setNotes(Array.isArray(noteData) ? noteData : []);
    } catch (error) {
      flash("error", error.message);
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => loadInvoices(historySearch), 250);
    return () => clearTimeout(timer);
  }, [historySearch, loadInvoices]);

  /* ---------------- cart ---------------- */

  const isExpired = (medicine) => medicine.expiry && medicine.expiry < todayText();

  const addToCart = (medicine) => {
    if (isExpired(medicine)) {
      flash("error", `${medicine.name} has expired and cannot be sold.`);
      return;
    }

    if (Number(medicine.quantity) <= 0) {
      flash("error", `${medicine.name} is out of stock.`);
      return;
    }

    setCart((current) => {
      const existing = current.find((line) => line.id === medicine.id);

      if (existing) {
        return current.map((line) =>
          line.id === medicine.id
            ? { ...line, quantity: Math.min(line.quantity + 1, line.stock) }
            : line
        );
      }

      return [
        ...current,
        {
          id: medicine.id,
          name: medicine.name,
          batch: medicine.batch,
          expiry: medicine.expiry,
          price: Number(medicine.price),
          stock: Number(medicine.quantity),
          gstRate: Number(medicine.gstRate ?? 5),
          quantity: 1,
          discountPercent: 0,
        },
      ];
    });

    setMessage({ type: "", text: "" });
    setQuery("");
    setResults([]);
    setShowResults(false);
    searchBox.current?.focus();
  };

  const updateLine = (id, changes) =>
    setCart((current) =>
      current.map((line) => (line.id === id ? { ...line, ...changes } : line))
    );

  const removeLine = (id) =>
    setCart((current) => current.filter((line) => line.id !== id));

  const clearBill = () => {
    setCart([]);
    setCustomer(EMPTY_CUSTOMER);
    setPicked(null);
    setAmountPaid("");
    setPaidMode("cash");
    setPaymentMode("cash");
    setMessage({ type: "", text: "" });
    searchBox.current?.focus();
  };

  /* ---------------- live totals ---------------- */

  const totals = useMemo(() => {
    const sum = { discount: 0, taxable: 0, cgst: 0, sgst: 0, grand: 0, units: 0 };

    cart.forEach((line) => {
      const amounts = computeLine({
        price: line.price,
        quantity: line.quantity,
        discountPercent: line.discountPercent,
        gstRate: line.gstRate,
        inclusive,
      });
      sum.discount += amounts.discount;
      sum.taxable += amounts.taxable;
      sum.cgst += amounts.cgst;
      sum.sgst += amounts.sgst;
      sum.grand += amounts.lineTotal;
      sum.units += line.quantity;
    });

    const payable = Math.round(sum.grand);
    return {
      discount: r2(sum.discount),
      taxable: r2(sum.taxable),
      cgst: r2(sum.cgst),
      sgst: r2(sum.sgst),
      roundOff: r2(payable - sum.grand),
      payable,
      units: sum.units,
    };
  }, [cart, inclusive]);

  const overStock = cart.some((line) => line.quantity > line.stock);

  const onCredit = paymentMode === "credit";
  const paidNow = onCredit ? Math.max(0, Number(amountPaid) || 0) : 0;
  const dueNow = onCredit ? Math.max(0, totals.payable - paidNow) : 0;
  const creditProblem = !onCredit
    ? ""
    : !picked
      ? "Choose or add a customer to sell on credit."
      : paidNow > totals.payable
        ? "The amount received is more than the bill total."
        : Number(picked.creditLimit) > 0 &&
            Math.max(0, Number(picked.balance) || 0) + dueNow > Number(picked.creditLimit)
          ? `This goes over ${picked.name}'s credit limit of ${inr(picked.creditLimit)}.`
          : "";

  /* ---------------- generate bill ---------------- */

  const generateBill = async () => {
    if (cart.length === 0) {
      flash("error", "Add at least one medicine to the bill.");
      return;
    }

    if (overStock) {
      flash("error", "One or more items are more than the stock available.");
      return;
    }

    if (creditProblem) {
      flash("error", creditProblem);
      return;
    }

    try {
      setSaving(true);
      setMessage({ type: "", text: "" });

      const data = await fetch(api("/api/invoices"), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          customerName: customer.name,
          customerPhone: customer.phone,
          customerGstin: customer.gstin,
          paymentMode,
          customerId: picked ? picked.id : undefined,
          amountPaid: onCredit ? paidNow : undefined,
          paidMode: onCredit && paidNow > 0 ? paidMode : undefined,
          clientRef: clientRef.current,
          items: cart.map((line) => ({
            medicineId: line.id,
            quantity: line.quantity,
            discountPercent: Number(line.discountPercent) || 0,
          })),
        }),
      }).then(readJson);

      clientRef.current = newRef();
      setOpenInvoice(data.invoice);
      clearBill();
      flash("success", `Bill ${data.invoice.invoiceNo} created. Stock has been updated.`);
      loadInvoices(historySearch);
      window.dispatchEvent(new Event("medtrack-auto-refresh"));
    } catch (error) {
      // The bill stays on screen; pressing the button again is always safe.
      flash("error", friendlyError(error));
    } finally {
      setSaving(false);
    }
  };

  /* ---------------- history actions ---------------- */

  const viewInvoice = async (id) => {
    try {
      const data = await fetch(api(`/api/invoices/${id}`)).then(readJson);
      setOpenInvoice(data.invoice);
    } catch (error) {
      flash("error", error.message);
    }
  };

  const viewNote = async (id) => {
    try {
      const data = await fetch(api(`/api/credit-notes/${id}`)).then(readJson);
      setOpenNote(data.creditNote);
    } catch (error) {
      flash("error", error.message);
    }
  };

  const startReturn = (invoice) => {
    setOpenInvoice(null);
    setReturnFor(invoice);
  };

  const returnDone = (creditNote) => {
    setReturnFor(null);
    setOpenNote(creditNote);
    flash("success", `Credit note ${creditNote.noteNo} created for ${inr(creditNote.totals.total)}.`);
    loadInvoices(historySearch);
    window.dispatchEvent(new Event("medtrack-auto-refresh"));
  };

  const cancelInvoice = async (invoice) => {
    if (
      !window.confirm(
        `Cancel invoice ${invoice.invoiceNo}? The medicines go back into stock and the sale is removed from reports.`
      )
    ) {
      return;
    }

    try {
      const data = await fetch(api(`/api/invoices/${invoice.id}/cancel`), {
        method: "POST",
      }).then(readJson);

      flash("success", data.message || "Invoice cancelled.");
      setOpenInvoice(null);
      loadInvoices(historySearch);
    } catch (error) {
      flash("error", error.message);
    }
  };

  /* ---------------- today's numbers ---------------- */

  const today = todayText();
  const todayBills = invoices.filter(
    (invoice) => invoice.status === "active" && invoice.date.startsWith(today)
  );
  const todayRefunds = notes
    .filter((note) => note.date.startsWith(today))
    .reduce((sum, note) => sum + note.total, 0);
  const todayRevenue =
    todayBills.reduce((sum, invoice) => sum + invoice.total, 0) - todayRefunds;
  const todayItems = todayBills.reduce((sum, invoice) => sum + invoice.itemCount, 0);

  /* ---------------- render ---------------- */

  return (
    <div className="bill-page">
      {message.text && (
        <div className={`bill-message ${message.type}`} role="status">
          {message.text}
          <button type="button" aria-label="Dismiss" onClick={() => setMessage({ type: "", text: "" })}>
            ×
          </button>
        </div>
      )}

      <section className="stats-grid bill-stats">
        <div className="stat-card">
          <div className="stat-icon green"><Icon name="wallet" size={21} /></div>
          <div>
            <span>Today's sales</span>
            <strong>{inr(todayRevenue)}</strong>
            <small>After returns{todayRefunds > 0 ? ` (${inr(todayRefunds)} refunded)` : ""}</small>
          </div>
        </div>
        <div className="stat-card">
          <div className="stat-icon blue"><Icon name="receipt" size={21} /></div>
          <div>
            <span>Bills today</span>
            <strong>{todayBills.length}</strong>
            <small>Cancelled bills not counted</small>
          </div>
        </div>
        <div className="stat-card">
          <div className="stat-icon orange"><Icon name="pill" size={21} /></div>
          <div>
            <span>Line items today</span>
            <strong>{todayItems}</strong>
            <small>Medicines on today's bills</small>
          </div>
        </div>
        <div className="stat-card">
          <div className="stat-icon red"><Icon name="trend" size={21} /></div>
          <div>
            <span>Average bill</span>
            <strong>{inr(todayBills.length ? todayRevenue / todayBills.length : 0)}</strong>
            <small>Today</small>
          </div>
        </div>
      </section>

      <div className="bill-grid">
        {/* ---------------- the bill being built ---------------- */}
        <section className="dashboard-card bill-main">
          <div className="card-header">
            <div>
              <h2>New bill</h2>
              <p>Search a medicine by name or ID and add it. You can add many.</p>
            </div>
            {cart.length > 0 && (
              <button type="button" className="view-button" onClick={clearBill}>
                Clear bill
              </button>
            )}
          </div>

          <div className="bill-search">
            <Icon name="pill" size={18} />
            <input
              ref={searchBox}
              type="search"
              value={query}
              placeholder="Search medicine name or ID"
              autoComplete="off"
              onChange={(event) => {
                setQuery(event.target.value);
                setShowResults(true);
              }}
              onFocus={() => setShowResults(true)}
              onKeyDown={(event) => {
                if (event.key === "Enter" && results.length > 0) {
                  event.preventDefault();
                  addToCart(results[0]);
                }
                if (event.key === "Escape") setShowResults(false);
              }}
            />

            {showResults && query.trim() && (
              <ul className="bill-results">
                {searching && results.length === 0 && <li className="note">Searching...</li>}
                {!searching && results.length === 0 && (
                  <li className="note">No medicine found for "{query.trim()}"</li>
                )}
                {results.map((medicine) => {
                  const expired = isExpired(medicine);
                  const out = Number(medicine.quantity) <= 0;
                  return (
                    <li key={medicine.id}>
                      <button
                        type="button"
                        disabled={expired || out}
                        onMouseDown={(event) => event.preventDefault()}
                        onClick={() => addToCart(medicine)}
                      >
                        <span className="name">
                          <strong>{medicine.name}</strong>
                          <small>
                            {medicine.id} · Batch {medicine.batch || "-"} · Exp {medicine.expiry || "-"}
                          </small>
                        </span>
                        <span className="meta">
                          <strong>{inr(medicine.price)}</strong>
                          <small className={expired || out ? "bad" : ""}>
                            {expired ? "Expired" : out ? "Out of stock" : `${medicine.quantity} in stock`}
                          </small>
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          {cart.length === 0 ? (
            <div className="bill-empty">
              <div className="empty-icon"><Icon name="receipt" size={24} /></div>
              <h3>The bill is empty</h3>
              <p>Search above and click a medicine to add it to this bill.</p>
            </div>
          ) : (
            <div className="bill-table-wrap">
              <table className="bill-table cards">
                <thead>
                  <tr>
                    <th className="left">Medicine</th>
                    <th>MRP</th>
                    <th>Qty</th>
                    <th>Disc %</th>
                    <th>GST</th>
                    <th className="right">Amount</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {cart.map((line) => {
                    const amounts = computeLine({
                      price: line.price,
                      quantity: line.quantity,
                      discountPercent: line.discountPercent,
                      gstRate: line.gstRate,
                      inclusive,
                    });
                    const tooMany = line.quantity > line.stock;

                    return (
                      <tr key={line.id} className={tooMany ? "warn" : ""}>
                        <td className="left lead">
                          <strong>{line.name}</strong>
                          <small>
                            {line.id} · Batch {line.batch || "-"} · Exp {line.expiry || "-"}
                          </small>
                          {tooMany && <small className="bad">Only {line.stock} in stock</small>}
                        </td>
                        <td data-label="MRP">{inr(line.price)}</td>
                        <td data-label="Qty">
                          <input
                            type="number"
                            min="1"
                            max={line.stock}
                            value={line.quantity}
                            aria-label={`Quantity of ${line.name}`}
                            onChange={(event) =>
                              updateLine(line.id, {
                                quantity: Math.max(1, parseInt(event.target.value, 10) || 1),
                              })
                            }
                          />
                        </td>
                        <td data-label="Disc %">
                          <input
                            type="number"
                            min="0"
                            max="100"
                            step="0.5"
                            value={line.discountPercent}
                            aria-label={`Discount percent for ${line.name}`}
                            onChange={(event) =>
                              updateLine(line.id, {
                                discountPercent: Math.min(
                                  100,
                                  Math.max(0, Number(event.target.value) || 0)
                                ),
                              })
                            }
                          />
                        </td>
                        <td data-label="GST">{line.gstRate}%</td>
                        <td className="right strong" data-label="Amount">{inr(amounts.lineTotal)}</td>
                        <td className="action">
                          <button
                            type="button"
                            className="bill-remove"
                            aria-label={`Remove ${line.name}`}
                            onClick={() => removeLine(line.id)}
                          >
                            ×
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {/* ---------------- summary ---------------- */}
        <aside className="dashboard-card bill-summary">
          <div className="card-header">
            <div>
              <h2>Bill summary</h2>
              <p>{cart.length} item{cart.length === 1 ? "" : "s"} · {totals.units} unit{totals.units === 1 ? "" : "s"}</p>
            </div>
          </div>

          <div className="bill-customer">
            <CustomerPicker picked={picked} onPick={setPicked} />
            {!picked && (
              <>
                <label>
                  Customer name
                  <input
                    value={customer.name}
                    placeholder="Walk-in Customer"
                    maxLength={80}
                    onChange={(event) => setCustomer({ ...customer, name: event.target.value })}
                  />
                </label>
                <div className="two">
                  <label>
                    Phone
                    <input
                      value={customer.phone}
                      placeholder="Optional"
                      maxLength={20}
                      inputMode="tel"
                      onChange={(event) => setCustomer({ ...customer, phone: event.target.value })}
                    />
                  </label>
                  <label>
                    Customer GSTIN
                    <input
                      value={customer.gstin}
                      placeholder="Optional"
                      maxLength={15}
                      onChange={(event) =>
                        setCustomer({ ...customer, gstin: event.target.value.toUpperCase() })
                      }
                    />
                  </label>
                </div>
              </>
            )}
          </div>

          <div className="bill-modes" role="radiogroup" aria-label="Payment mode">
            {PAYMENT_MODES.map((mode) => (
              <button
                key={mode.value}
                type="button"
                role="radio"
                aria-checked={paymentMode === mode.value}
                className={paymentMode === mode.value ? "active" : ""}
                onClick={() => setPaymentMode(mode.value)}
              >
                {mode.label}
              </button>
            ))}
          </div>

          {onCredit && (
            <div className="bill-credit">
              <div className="two">
                <label>
                  Received now (₹)
                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    inputMode="decimal"
                    value={amountPaid}
                    placeholder="0 = all on udhaar"
                    onChange={(event) => setAmountPaid(event.target.value)}
                  />
                </label>
                <label>
                  Received by
                  <select value={paidMode} onChange={(event) => setPaidMode(event.target.value)}>
                    <option value="cash">Cash</option>
                    <option value="upi">UPI</option>
                    <option value="card">Card</option>
                  </select>
                </label>
              </div>
              <p className={creditProblem ? "bad" : ""}>
                {creditProblem || `Goes on ${picked.name}'s udhaar: ${inr(dueNow)}`}
              </p>
            </div>
          )}

          <dl className="bill-totals">
            <div><dt>Taxable value</dt><dd>{inr(totals.taxable)}</dd></div>
            <div><dt>CGST</dt><dd>{inr(totals.cgst)}</dd></div>
            <div><dt>SGST</dt><dd>{inr(totals.sgst)}</dd></div>
            {totals.discount > 0 && <div><dt>Discount</dt><dd>- {inr(totals.discount)}</dd></div>}
            {totals.roundOff !== 0 && (
              <div><dt>Round off</dt><dd>{totals.roundOff > 0 ? "+" : ""}{totals.roundOff.toFixed(2)}</dd></div>
            )}
            <div className="grand"><dt>Total payable</dt><dd>{inr(totals.payable)}</dd></div>
          </dl>

          <p className="bill-note">
            {inclusive ? "Prices include GST." : "GST is added on top of the price."} Final
            amounts are calculated again when the bill is saved.
          </p>

          <button
            type="button"
            className="primary-button bill-generate"
            disabled={saving || cart.length === 0 || overStock || Boolean(creditProblem)}
            onClick={generateBill}
          >
            {saving ? "Saving bill..." : `Generate bill · ${inr(totals.payable)}`}
          </button>
        </aside>
      </div>

      {/* ---------------- history ---------------- */}
      <section className="dashboard-card bill-history">
        <div className="card-header">
          <div>
            <h2>Recent bills</h2>
            <p>Open a bill to print it again</p>
          </div>
          <input
            type="search"
            className="bill-history-search"
            value={historySearch}
            placeholder="Search bill no., customer or phone"
            onChange={(event) => setHistorySearch(event.target.value)}
          />
        </div>

        {historyLoading && invoices.length === 0 ? (
          <div className="bill-empty small">Loading bills...</div>
        ) : invoices.length === 0 ? (
          <div className="bill-empty small">
            {historySearch ? "No bills match your search." : "No bills yet. Your first bill will show up here."}
          </div>
        ) : (
          <div className="bill-table-wrap">
            <table className="bill-table history cards">
              <thead>
                <tr>
                  <th className="left">Bill no.</th>
                  <th className="left">Date</th>
                  <th className="left">Customer</th>
                  <th>Items</th>
                  <th>Payment</th>
                  <th className="right">Total</th>
                  <th>Status</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {invoices.map((invoice) => (
                  <tr key={invoice.id} className={invoice.status === "cancelled" ? "cancelled" : ""}>
                    <td className="left strong lead">{invoice.invoiceNo}</td>
                    <td className="left" data-label="Date">{formatInvoiceDate(invoice.date)}</td>
                    <td className="left" data-label="Customer">{invoice.customerName}</td>
                    <td data-label="Items">{invoice.itemCount}</td>
                    <td data-label="Payment">{modeLabel(invoice.paymentMode)}</td>
                    <td className="right strong" data-label="Total">{inr(invoice.total)}</td>
                    <td data-label="Status">
                      {(() => {
                        const fullyReturned =
                          invoice.status === "active" && invoice.returnedTotal >= invoice.total;
                        const partlyReturned =
                          invoice.status === "active" && invoice.returnedTotal > 0 && !fullyReturned;
                        const kind =
                          invoice.status === "cancelled"
                            ? "cancelled"
                            : fullyReturned
                              ? "returned"
                              : partlyReturned
                                ? "partial"
                                : "active";
                        const label = {
                          cancelled: "Cancelled",
                          returned: "Returned",
                          partial: "Part returned",
                          active: invoice.paymentMode === "credit" ? "On udhaar" : "Paid",
                        }[kind];
                        return <span className={`bill-status ${kind}`}>{label}</span>;
                      })()}
                    </td>
                    <td className="right action">
                      <button type="button" className="secondary-button" onClick={() => viewInvoice(invoice.id)}>
                        View / Print
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* ---------------- credit notes (returns) ---------------- */}
      <section className="dashboard-card bill-history">
        <div className="card-header">
          <div>
            <h2>Returns &amp; credit notes</h2>
            <p>
              {canCancel
                ? "To return medicines, open the bill and choose Return items"
                : "Returns are made by the owner or pharmacist"}
            </p>
          </div>
        </div>

        {notes.length === 0 ? (
          <div className="bill-empty small">No returns yet.</div>
        ) : (
          <div className="bill-table-wrap">
            <table className="bill-table history cards">
              <thead>
                <tr>
                  <th className="left">Credit note</th>
                  <th className="left">Date</th>
                  <th className="left">Against bill</th>
                  <th className="left">Customer</th>
                  <th className="left">Reason</th>
                  <th className="right">Refund</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {notes.map((note) => (
                  <tr key={note.id}>
                    <td className="left strong lead">{note.noteNo}</td>
                    <td className="left" data-label="Date">{formatInvoiceDate(note.date)}</td>
                    <td className="left" data-label="Against bill">{note.invoiceNo}</td>
                    <td className="left" data-label="Customer">{note.customerName}</td>
                    <td className="left" data-label="Reason">{note.reason}</td>
                    <td className="right strong" data-label="Refund">{inr(note.total)}</td>
                    <td className="right action">
                      <button type="button" className="secondary-button" onClick={() => viewNote(note.id)}>
                        View / Print
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {returnFor && (
        <ReturnDialog
          invoice={returnFor}
          onClose={() => setReturnFor(null)}
          onDone={returnDone}
        />
      )}

      <CreditNoteModal note={openNote} onClose={() => setOpenNote(null)} />

      <InvoiceModal
        invoice={openInvoice}
        canCancel={canCancel}
        onReturn={startReturn}
        onClose={() => setOpenInvoice(null)}
        onNewBill={() => {
          setOpenInvoice(null);
          searchBox.current?.focus();
        }}
        onCancelInvoice={cancelInvoice}
      />
    </div>
  );
}

export default SalesBilling;
