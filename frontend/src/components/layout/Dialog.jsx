import { useEffect } from "react";
import { createPortal } from "react-dom";

/** The small pop-up used by Customers, Suppliers and Admin. A bottom sheet on phones. */
function Dialog({ title, subtitle, onClose, wide, children }) {
  useEffect(() => {
    const onKey = (event) => event.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return createPortal(
    <div className="return-overlay no-print" role="dialog" aria-modal="true" aria-label={title}>
      <div className={`return-card cust-dialog${wide ? " wide" : ""}`}>
        <div className="return-head">
          <div>
            <h2>{title}</h2>
            {subtitle && <p>{subtitle}</p>}
          </div>
          <button type="button" className="return-close" aria-label="Close" onClick={onClose}>
            ×
          </button>
        </div>
        {children}
      </div>
    </div>,
    document.body
  );
}

export default Dialog;
