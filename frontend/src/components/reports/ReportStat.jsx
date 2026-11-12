

function ReportStat({ icon, title, value, note }) {
  return <div className="report-stat"><div className="report-stat-icon">{icon}</div><div><span>{title}</span><strong>{value}</strong><small>{note}</small></div></div>;
}

/* =========================================================
   SETTINGS
========================================================= */

export default ReportStat;
