import { createPortal } from "react-dom";
import {
  amountInWords,
  billMessage,
  formatInvoiceDate,
  inr,
  modeLabel,
  whatsappLink,
} from "../../utils/invoiceUtils.js";

/** GST summary grouped by tax rate (printed at the bottom of a GST document). */
function groupByRate(items) {
  const byRate = {};

  items.forEach((item) => {
    const key = String(item.gstRate);
    if (!byRate[key]) {
      byRate[key] = { rate: item.gstRate, taxable: 0, cgst: 0, sgst: 0 };
    }
    byRate[key].taxable += item.taxable;
    byRate[key].cgst += item.cgst;
    byRate[key].sgst += item.sgst;
  });

  return Object.values(byRate).sort((a, b) => a.rate - b.rate);
}

export function SellerHeader({ seller, title, note }) {
  return (
    <header className="invoice-head">
      <div>
        <h1>{seller.name}</h1>
        {seller.address && <p>{seller.address}</p>}
        <p>
          {seller.phone && <span>Phone: {seller.phone}</span>}
          {seller.state && <span>State: {seller.state}</span>}
        </p>
        <p>
          {seller.gstin && <span>GSTIN: <strong>{seller.gstin}</strong></span>}
          {seller.license && <span>Drug Licence: <strong>{seller.license}</strong></span>}
        </p>
      </div>
      <div className="invoice-title">
        <strong>{title}</strong>
        <span>{note}</span>
      </div>
    </header>
  );
}

function GstSummary({ rows }) {
  return (
    <table className="invoice-gst-summary">
      <thead>
        <tr>
          <th>GST %</th>
          <th>Taxable</th>
          <th>CGST</th>
          <th>SGST</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.rate}>
            <td>{row.rate}%</td>
            <td>{row.taxable.toFixed(2)}</td>
            <td>{row.cgst.toFixed(2)}</td>
            <td>{row.sgst.toFixed(2)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function LinesTable({ items, showExpiry }) {
  return (
    <table className="invoice-table">
      <thead>
        <tr>
          <th>#</th>
          <th className="left">Description</th>
          <th>HSN</th>
          <th>Qty</th>
          <th>MRP</th>
          <th>Disc.</th>
          <th>Taxable</th>
          <th>GST%</th>
          <th>CGST</th>
          <th>SGST</th>
          <th>Amount</th>
        </tr>
      </thead>
      <tbody>
        {items.map((item, index) => (
          <tr key={`${item.medicineId}-${index}`}>
            <td>{index + 1}</td>
            <td className="left">
              <strong>{item.name}</strong>
              <small>
                Batch {item.batch || "-"}
                {showExpiry ? ` · Exp ${item.expiry || "-"}` : ""}
                {item.returnedQuantity > 0 ? ` · ${item.returnedQuantity} returned` : ""}
              </small>
            </td>
            <td>{item.hsn}</td>
            <td>{item.quantity}</td>
            <td>{item.unitPrice.toFixed(2)}</td>
            <td>{item.discountPercent ? `${item.discountPercent}%` : "-"}</td>
            <td>{item.taxable.toFixed(2)}</td>
            <td>{item.gstRate}%</td>
            <td>{item.cgst.toFixed(2)}</td>
            <td>{item.sgst.toFixed(2)}</td>
            <td className="strong">{item.lineTotal.toFixed(2)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Totals({ totals, grandLabel }) {
  return (
    <dl className="invoice-totals">
      <div><dt>Taxable value</dt><dd>{inr(totals.taxable)}</dd></div>
      <div><dt>CGST</dt><dd>{inr(totals.cgst)}</dd></div>
      <div><dt>SGST</dt><dd>{inr(totals.sgst)}</dd></div>
      {totals.discount > 0 && (
        <div><dt>Discount given</dt><dd>{inr(totals.discount)}</dd></div>
      )}
      {totals.roundOff !== 0 && (
        <div><dt>Round off</dt><dd>{totals.roundOff > 0 ? "+" : ""}{totals.roundOff.toFixed(2)}</dd></div>
      )}
      <div className="grand"><dt>{grandLabel}</dt><dd>{inr(totals.total)}</dd></div>
    </dl>
  );
}

/** The printable GST invoice. Amounts come straight from the saved invoice. */
export function InvoiceSheet({ invoice }) {
  const { seller, customer, items, totals } = invoice;
  const cancelled = invoice.status === "cancelled";
  const returns = invoice.creditNotes || [];

  return (
    <div className={`invoice-sheet${cancelled ? " cancelled" : ""}`}>
      {cancelled && <div className="invoice-watermark">CANCELLED</div>}

      <SellerHeader
        seller={seller}
        title="TAX INVOICE"
        note={invoice.pricesIncludeGst ? "Prices include GST" : "GST charged extra"}
      />

      <section className="invoice-meta">
        <div>
          <span>Invoice No.</span>
          <strong>{invoice.invoiceNo}</strong>
          <span>Date</span>
          <strong>{formatInvoiceDate(invoice.date)}</strong>
          <span>Payment</span>
          <strong>{modeLabel(invoice.paymentMode)}</strong>
        </div>
        <div>
          <span>Billed to</span>
          <strong>{customer.name}</strong>
          {customer.phone && (<><span>Phone</span><strong>{customer.phone}</strong></>)}
          {customer.gstin && (<><span>Customer GSTIN</span><strong>{customer.gstin}</strong></>)}
        </div>
      </section>

      <LinesTable items={items} showExpiry />

      <section className="invoice-bottom">
        <div className="invoice-words">
          <span>Amount in words</span>
          <strong>{amountInWords(totals.total)}</strong>
          <GstSummary rows={groupByRate(items)} />
        </div>

        <div>
          <Totals totals={totals} grandLabel="Total payable" />
          {invoice.paymentMode === "credit" && !cancelled && (
            <dl className="invoice-totals invoice-credit">
              <div><dt>Paid now</dt><dd>{inr(invoice.amountPaid)}</dd></div>
              <div className="grand"><dt>Balance due (udhaar)</dt><dd>{inr(Math.max(0, totals.total - invoice.amountPaid))}</dd></div>
            </dl>
          )}
        </div>
      </section>

      {returns.length > 0 && (
        <section className="invoice-returns">
          <strong>Returns against this invoice</strong>
          {returns.map((note) => (
            <span key={note.id}>
              {note.noteNo} · {formatInvoiceDate(note.date)} · {inr(note.total)}
            </span>
          ))}
          <span className="net">
            Net after returns: {inr(Math.max(0, totals.total - invoice.returnedTotal))}
          </span>
        </section>
      )}

      <footer className="invoice-foot">
        <p>
          Goods once sold will not be taken back without a valid bill.
          Please check medicines and expiry dates before leaving the counter.
        </p>
        <div className="invoice-sign">
          <span>For {seller.name}</span>
          <strong>Authorised signatory</strong>
        </div>
      </footer>
    </div>
  );
}

/** The printable GST credit note issued for a return. */
export function CreditNoteSheet({ note }) {
  const { seller, customer, items, totals } = note;

  return (
    <div className="invoice-sheet credit">
      <SellerHeader
        seller={seller}
        title="CREDIT NOTE"
        note="Against a tax invoice"
      />

      <section className="invoice-meta">
        <div>
          <span>Credit note no.</span>
          <strong>{note.noteNo}</strong>
          <span>Date</span>
          <strong>{formatInvoiceDate(note.date)}</strong>
          <span>Against invoice</span>
          <strong>{note.invoice.invoiceNo} ({formatInvoiceDate(note.invoice.date)})</strong>
        </div>
        <div>
          <span>Customer</span>
          <strong>{customer.name}</strong>
          {customer.phone && (<><span>Phone</span><strong>{customer.phone}</strong></>)}
          {customer.gstin && (<><span>Customer GSTIN</span><strong>{customer.gstin}</strong></>)}
          <span>Reason</span>
          <strong>{note.reason}</strong>
        </div>
      </section>

      <LinesTable items={items} showExpiry={false} />

      <section className="invoice-bottom">
        <div className="invoice-words">
          <span>Refund in words</span>
          <strong>{amountInWords(totals.total)}</strong>
          <span>Refunded by</span>
          <strong className="cap">{note.refundMode}</strong>
          <span>Stock</span>
          <strong>{note.restocked ? "Returned medicines added back to stock" : "Not added back to stock"}</strong>
          <GstSummary rows={groupByRate(items)} />
        </div>

        <Totals totals={totals} grandLabel="Total refund" />
      </section>

      <footer className="invoice-foot">
        <p>
          This credit note reduces the sale value and GST of the invoice named above.
        </p>
        <div className="invoice-sign">
          <span>For {seller.name}</span>
          <strong>Authorised signatory</strong>
        </div>
      </footer>
    </div>
  );
}

/** Full-screen shell with a toolbar. Rendered in a portal so printing shows only the document. */
export function DocumentModal({ label, status, actions, children, onClose }) {
  return createPortal(
    <div className="invoice-modal" role="dialog" aria-modal="true" aria-label={label}>
      <div className="invoice-toolbar no-print">
        <div>
          <strong>{label}</strong>
          <span>{status}</span>
        </div>
        <div className="invoice-actions">
          {actions}
          <button type="button" className="secondary-button" onClick={onClose}>
            Close
          </button>
          <button type="button" className="primary-button" onClick={() => window.print()}>
            Print
          </button>
        </div>
      </div>

      <div className="invoice-scroll">{children}</div>
    </div>,
    document.body
  );
}

export function InvoiceModal({
  invoice,
  onClose,
  onNewBill,
  onCancelInvoice,
  onReturn,
  canCancel,
}) {
  if (!invoice) return null;

  const hasReturns = (invoice.creditNotes || []).length > 0;
  const canReturnMore =
    invoice.status === "active" &&
    invoice.items.some((item) => item.quantity - item.returnedQuantity > 0);

  const status =
    invoice.status === "cancelled"
      ? "Cancelled"
      : hasReturns
        ? "Has returns"
        : "Ready to print";

  return (
    <DocumentModal
      label={invoice.invoiceNo}
      status={status}
      onClose={onClose}
      actions={
        <>
          {canCancel && invoice.status === "active" && !hasReturns && (
            <button type="button" className="delete-button" onClick={() => onCancelInvoice(invoice)}>
              Cancel invoice
            </button>
          )}
          {canCancel && canReturnMore && (
            <button type="button" className="secondary-button" onClick={() => onReturn(invoice)}>
              Return items
            </button>
          )}
          {invoice.status === "active" && (
            <a
              className="secondary-button invoice-share"
              href={whatsappLink(invoice.customer.phone, billMessage(invoice))}
              target="_blank"
              rel="noopener noreferrer"
            >
              WhatsApp
            </a>
          )}
          {onNewBill && (
            <button type="button" className="secondary-button" onClick={onNewBill}>
              New bill
            </button>
          )}
        </>
      }
    >
      <InvoiceSheet invoice={invoice} />
    </DocumentModal>
  );
}

export function CreditNoteModal({ note, onClose }) {
  if (!note) return null;

  return (
    <DocumentModal
      label={note.noteNo}
      status="Credit note"
      onClose={onClose}
      actions={null}
    >
      <CreditNoteSheet note={note} />
    </DocumentModal>
  );
}
