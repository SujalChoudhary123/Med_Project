  function StatCard({
    icon,
    iconClass,
    title,
    value,
    description,
    loading = false,
  }) {

    return (

      <div className="stat-card">

        <div
          className={`stat-icon ${iconClass}`}
        >
          {icon}
        </div>

        <div>

          <span>
            {title}
          </span>

          <strong>
            {loading
              ? "..."
              : value}
          </strong>

          <small>
            {description}
          </small>

        </div>

      </div>

    );

  }

export default StatCard;
