package launchday.config;

/** Central place for all environment configuration. */
public final class Config {
    public final String dbUrl;
    public final String dbUser;
    public final String dbPass;
    public final String authorityUrl;
    public final int port;
    public final int poolSize;
    public final int standinMaxPerItem;
    public final int breakerPollMs;
    public final int breakerHealthyChecks;

    private Config() {
        this.dbUrl = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/student");
        this.dbUser = env("DATABASE_USER", "launchday");
        this.dbPass = env("DATABASE_PASSWORD", "launchday");
        this.authorityUrl = env("AUTHORITY_URL", "http://127.0.0.1:9000").replaceAll("/$", "");
        this.port = Integer.parseInt(env("PORT", "8080"));
        this.poolSize = Integer.parseInt(env("DB_POOL_SIZE", "32"));
        this.standinMaxPerItem = Integer.parseInt(env("STANDIN_MAX_PER_ITEM", "10"));
        this.breakerPollMs = Integer.parseInt(env("AUTHORITY_BREAKER_POLL_MS", "1000"));
        this.breakerHealthyChecks = Integer.parseInt(env("AUTHORITY_BREAKER_HEALTHY_CHECKS", "3"));
    }

    public static Config fromEnv() {
        return new Config();
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isEmpty() ? def : v;
    }
}
