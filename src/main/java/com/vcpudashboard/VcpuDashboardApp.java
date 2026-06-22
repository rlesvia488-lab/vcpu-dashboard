package com.vcpudashboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VcpuDashboardApp {
    private static final Pattern VCPU_PATTERN = Pattern.compile("(?i)(\\d+)\\s*vCPU(?:s)?");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final RuntimeConfig runtime = RuntimeConfig.load();
    private final TraceLog trace = new TraceLog(Path.of(value("VCPU_DASHBOARD_LOG", "vcpu.dashboard.log", "vcpu-dashboard.log")));
    private final boolean traceOcsBodies = Boolean.parseBoolean(value("TRACE_OCS_RESPONSE_BODIES", "vcpu.dashboard.trace-ocs-bodies", "false"));
    private volatile DashboardSnapshot snapshot = DashboardSnapshot.loading();
    private volatile String vaultToken = "";
    private volatile Instant vaultTokenRefreshAt = Instant.EPOCH;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            selfTest();
            return;
        }
        VcpuDashboardApp app = new VcpuDashboardApp();
        int port = Integer.parseInt(value("VCPU_DASHBOARD_PORT", "vcpu.dashboard.port", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/dashboard", app::dashboardApi);
        server.createContext("/api/health", app::healthApi);
        server.createContext("/", app::index);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        app.trace.info("STARTUP", "VCPU Dashboard listening on http://0.0.0.0:" + port);
        app.trace.info("CONFIG", "demoMode=" + app.runtime.demoMode + " vaultUri=" + safeUri(app.runtime.vaultUri)
                + " namespace=" + blankLabel(app.runtime.namespace) + " secretPath=" + blankLabel(app.runtime.secretPath)
                + " roleId=" + (app.runtime.roleId.isBlank() ? "<missing>" : "<configured>")
                + " secretId=" + (app.runtime.secretId.isBlank() ? "<missing>" : "<configured>")
                + " log=" + app.trace.path.toAbsolutePath() + " traceOcsBodies=" + app.traceOcsBodies);
        app.refreshAsync();
        long refreshSeconds = Long.parseLong(value("REFRESH_INTERVAL_SECONDS", "vcpu.dashboard.refresh-seconds", "300"));
        if (refreshSeconds > 0) {
            Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "dashboard-scheduler"); t.setDaemon(true); return t; })
                    .scheduleAtFixedRate(app::refreshAsync, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
            app.trace.info("STARTUP", "Automatic inventory refresh scheduled every " + refreshSeconds + " seconds");
        }
    }

    private void index(HttpExchange ex) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) { send(ex, 404, "text/plain", "Not found"); return; }
        send(ex, 200, "text/html; charset=utf-8", Html.INDEX);
    }

    private void healthApi(HttpExchange ex) throws IOException {
        send(ex, 200, "application/json", "{\"status\":\"UP\",\"dataState\":\"" + esc(snapshot.state)
                + "\",\"message\":\"" + esc(snapshot.message) + "\",\"updatedAt\":\"" + snapshot.updatedAt + "\"}");
    }

    private void dashboardApi(HttpExchange ex) throws IOException {
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            refreshAsync();
            send(ex, 202, "application/json", snapshot.toJson());
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { send(ex, 405, "application/json", "{\"error\":\"Method not allowed\"}"); return; }
        send(ex, 200, "application/json", snapshot.toJson());
    }

    private synchronized void refreshAsync() {
        if ("refreshing".equals(snapshot.state)) { trace.info("REFRESH", "Refresh ignored because one is already running"); return; }
        DashboardSnapshot prior = snapshot;
        snapshot = prior.withState("refreshing", "Refreshing Vault and OCS data...");
        trace.info("REFRESH", "Inventory refresh started; previousState=" + prior.state + " previousAccounts=" + prior.accounts.size());
        Thread refreshThread = new Thread(() -> {
            try {
                snapshot = refresh();
                trace.info("REFRESH", "Inventory refresh completed; state=" + snapshot.state + " accounts=" + snapshot.accounts.size()
                        + " servers=" + snapshot.accounts.stream().mapToInt(AccountSummary::servers).sum()
                        + " vcpus=" + snapshot.accounts.stream().mapToInt(AccountSummary::vcpus).sum());
            } catch (Exception e) {
                String message = "Inventory refresh failed: " + clean(e);
                snapshot = new DashboardSnapshot("error", message, Instant.now(), prior.accounts);
                trace.error("REFRESH", message, e);
            }
        }, "dashboard-refresh");
        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    private DashboardSnapshot refresh() throws Exception {
        if (runtime.demoMode) { trace.info("REFRESH", "DEMO_MODE is enabled; no Vault or OCS calls will be made"); return demoSnapshot(); }
        SecretConfig secret = loadSecret();
        trace.info("VAULT", "Configuration loaded: accounts=" + secret.accounts.size() + " endpoints=" + secret.endpoints.size()
                + " oauthUrl=" + safeUri(secret.oauthUrl));
        List<AccountSummary> summaries = new ArrayList<>();
        for (TechnicalAccount account : secret.accounts) {
            try {
                trace.info("ACCOUNT", "Starting account=" + account.name + " accountId=" + account.accountId);
                String token = oauthToken(account, secret.oauthUrl);
                summaries.add(summarizeAccount(account, token, secret.endpoints));
            } catch (Exception e) {
                trace.error("ACCOUNT", "Account failed name=" + account.name + " error=" + clean(e), e);
                summaries.add(new AccountSummary(account.name, account.accountId, 0, 0, Map.of(), Map.of(), clean(e)));
            }
        }
        summaries.sort(Comparator.comparing(AccountSummary::name, String.CASE_INSENSITIVE_ORDER));
        long failures = summaries.stream().filter(a -> !a.error.isBlank()).count();
        String state = failures == 0 ? "ready" : failures == summaries.size() ? "error" : "partial";
        String message = failures == 0 ? "Live inventory" : failures + " of " + summaries.size() + " accounts have errors; check vcpu-dashboard.log";
        return new DashboardSnapshot(state, message, Instant.now(), summaries);
    }

    private AccountSummary summarizeAccount(TechnicalAccount account, String token, List<OcsEndpoint> endpoints) {
        int total = 0, serverCount = 0;
        Map<String, Integer> regions = new LinkedHashMap<>(), flavors = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (OcsEndpoint endpoint : endpoints) {
            try {
                long started = System.nanoTime();
                trace.info("OCS REQUEST", "GET " + safeUri(endpoint.serversUrl) + " account=" + account.name
                        + " region=" + endpoint.region + " Authorization=Bearer [REDACTED]");
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.serversUrl)).timeout(Duration.ofSeconds(20))
                        .header("Accept", "application/json").header("Authorization", "Bearer " + token).GET().build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                trace.info("OCS RESPONSE", "GET " + safeUri(endpoint.serversUrl) + " account=" + account.name
                        + " region=" + endpoint.region + " status=" + response.statusCode() + " durationMs=" + elapsedMs(started)
                        + " bodyBytes=" + response.body().getBytes(StandardCharsets.UTF_8).length);
                if (traceOcsBodies) trace.info("OCS BODY", "account=" + account.name + " region=" + endpoint.region + " body=" + response.body());
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    trace.info("OCS ERROR BODY", "account=" + account.name + " region=" + endpoint.region + " body=" + redactedBody(response.body()));
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("HTTP " + response.statusCode());
                List<ServerFlavor> servers = parseServers(response.body());
                int regionVcpus = servers.stream().mapToInt(ServerFlavor::vcpus).sum();
                trace.info("OCS PARSE", "account=" + account.name + " region=" + endpoint.region + " servers=" + servers.size()
                        + " vcpus=" + regionVcpus + " flavors=" + flavorSummary(servers));
                total += regionVcpus; serverCount += servers.size(); regions.merge(endpoint.region, regionVcpus, Integer::sum);
                for (ServerFlavor server : servers) flavors.merge(server.originalName, server.vcpus, Integer::sum);
            } catch (Exception e) {
                failures.add(endpoint.region + ": " + clean(e));
                trace.error("OCS ERROR", "account=" + account.name + " region=" + endpoint.region + " url=" + safeUri(endpoint.serversUrl)
                        + " error=" + clean(e), e);
            }
        }
        trace.info("ACCOUNT", "Completed account=" + account.name + " servers=" + serverCount + " vcpus=" + total + " failures=" + failures.size());
        return new AccountSummary(account.name, account.accountId, total, serverCount, regions, flavors, String.join(" | ", failures));
    }

    @SuppressWarnings("unchecked")
    static List<ServerFlavor> parseServers(String json) throws IOException {
        Object parsed = new JsonParser(json).parse();
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("servers") instanceof Collection<?> servers)) throw new IOException("OCS response has no servers array");
        List<ServerFlavor> result = new ArrayList<>();
        for (Object item : servers) {
            if (!(item instanceof Map<?, ?> server) || !(server.get("flavor") instanceof Map<?, ?> flavor)) continue;
            String originalName = string(flavor.get("original_name"));
            int vcpus = extractVcpus(originalName);
            if (vcpus == 0 && flavor.get("vcpus") instanceof Number n) vcpus = n.intValue();
            if (vcpus > 0) result.add(new ServerFlavor(originalName.isBlank() ? "Unknown flavor" : originalName, vcpus));
        }
        return result;
    }

    static int extractVcpus(String originalName) {
        Matcher matcher = VCPU_PATTERN.matcher(originalName == null ? "" : originalName);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private String oauthToken(TechnicalAccount account, String url) throws Exception {
        String scope = account.accountId + ":sgcp:ocs:read";
        String body = "grant_type=client_credentials&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8);
        String basic = Base64.getEncoder().encodeToString((account.clientId + ":" + account.clientSecret).getBytes(StandardCharsets.UTF_8));
        long started = System.nanoTime();
        trace.info("OAUTH REQUEST", "POST " + safeUri(url) + " account=" + account.name
                + " accountId=" + account.accountId + " Authorization=Basic [REDACTED] scopes=" + scope);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Basic " + basic).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        trace.info("OAUTH RESPONSE", "POST " + safeUri(url) + " account=" + account.name + " status=" + response.statusCode()
                + " durationMs=" + elapsedMs(started) + " bodyBytes=" + response.body().getBytes(StandardCharsets.UTF_8).length);
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            trace.info("OAUTH ERROR BODY", "account=" + account.name + " body=" + redactedBody(response.body()));
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("OAuth HTTP " + response.statusCode());
        String token = findString(new JsonParser(response.body()).parse(), "access_token");
        if (token.isBlank()) throw new IOException("OAuth response has no access_token");
        trace.info("OAUTH", "Access token received account=" + account.name + " length=" + token.length() + " value=[REDACTED]");
        return token;
    }

    private SecretConfig loadSecret() throws Exception {
        if (!runtime.complete()) {
            trace.info("VAULT", "Configuration incomplete: uri=" + !runtime.vaultUri.isBlank() + " path=" + !runtime.secretPath.isBlank()
                    + " roleId=" + !runtime.roleId.isBlank() + " secretId=" + !runtime.secretId.isBlank());
            throw new IOException("Vault configuration is incomplete");
        }
        ensureVaultToken();
        Exception last = null;
        for (String path : vaultPaths(runtime.secretPath)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    long started = System.nanoTime();
                    String requestUrl = stripSlash(runtime.vaultUri) + path;
                    trace.info("VAULT REQUEST", "GET " + safeUri(requestUrl) + " attempt=" + (attempt + 1)
                            + " namespace=" + blankLabel(runtime.namespace) + " X-Vault-Token=[REDACTED]");
                    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(requestUrl)).timeout(Duration.ofSeconds(15))
                            .header("X-Vault-Token", vaultToken).GET();
                    if (!runtime.namespace.isBlank()) builder.header("X-Vault-Namespace", runtime.namespace);
                    HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    trace.info("VAULT RESPONSE", "GET " + safeUri(requestUrl) + " status=" + response.statusCode()
                            + " durationMs=" + elapsedMs(started) + " body=[REDACTED: contains credentials]");
                    if ((response.statusCode() == 401 || response.statusCode() == 403) && attempt == 0) {
                        trace.info("VAULT", "Vault rejected cached token; forcing AppRole login and retrying once");
                        vaultToken = "";
                        ensureVaultToken();
                        continue;
                    }
                    if (response.statusCode() == 404) break;
                    if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("Vault secret HTTP " + response.statusCode());
                    Object data = vaultPayload(new JsonParser(response.body()).parse());
                    List<TechnicalAccount> accounts = new ArrayList<>(); collectAccounts(data, "", accounts, new HashSet<>());
                    List<OcsEndpoint> endpoints = new ArrayList<>(); collectEndpoints(data, endpoints, new HashSet<>());
                    String oauth = findFirst(data, "cmaas_oauth_token_url", "cmaasOauthTokenUrl", "oauth_token_url", "oauthTokenUrl");
                    if (accounts.isEmpty() || endpoints.isEmpty() || oauth.isBlank()) throw new IOException("Vault secret is missing accounts, OCS endpoints, or OAuth URL");
                    trace.info("VAULT PARSE", "Secret parsed path=" + path + " accounts=" + accounts.stream().map(TechnicalAccount::name).toList()
                            + " endpoints=" + endpoints.stream().map(e -> e.region + "=" + safeUri(e.serversUrl)).toList()
                            + " oauthUrl=" + safeUri(oauth) + " credentials=[REDACTED]");
                    return new SecretConfig(oauth, accounts, endpoints);
                } catch (Exception e) { last = e; trace.error("VAULT ERROR", "Secret read path=" + path + " failed: " + clean(e), e); break; }
            }
        }
        throw new IOException(last == null ? "Vault secret was not found" : clean(last));
    }

    private synchronized void ensureVaultToken() throws Exception {
        if (!vaultToken.isBlank() && Instant.now().isBefore(vaultTokenRefreshAt)) {
            trace.info("VAULT", "Using cached Vault token; refreshAt=" + vaultTokenRefreshAt + " value=[REDACTED]");
            return;
        }
        String body = "{\"role_id\":\"" + esc(runtime.roleId) + "\",\"secret_id\":\"" + esc(runtime.secretId) + "\"}";
        String loginUrl = stripSlash(runtime.vaultUri) + "/v1/auth/approle/login";
        long started = System.nanoTime();
        trace.info("VAULT REQUEST", "POST " + safeUri(loginUrl) + " namespace=" + blankLabel(runtime.namespace)
                + " role_id=[REDACTED] secret_id=[REDACTED]");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(loginUrl))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (!runtime.namespace.isBlank()) builder.header("X-Vault-Namespace", runtime.namespace);
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        trace.info("VAULT RESPONSE", "POST " + safeUri(loginUrl) + " status=" + response.statusCode()
                + " durationMs=" + elapsedMs(started) + " body=[REDACTED: contains token]");
        Object parsed = new JsonParser(response.body()).parse();
        vaultToken = findString(parsed, "client_token");
        long lease = findNumber(parsed, "lease_duration");
        if (response.statusCode() < 200 || response.statusCode() >= 300 || vaultToken.isBlank()) throw new IOException("Vault AppRole login HTTP " + response.statusCode());
        vaultTokenRefreshAt = lease > 0 ? Instant.now().plusSeconds(Math.max(1, lease - Math.min(30, lease / 5))) : Instant.now().plusSeconds(300);
        trace.info("VAULT", "AppRole login successful tokenLength=" + vaultToken.length() + " leaseSeconds=" + lease
                + " refreshAt=" + vaultTokenRefreshAt + " value=[REDACTED]");
    }

    private static void collectAccounts(Object value, String suggested, List<TechnicalAccount> out, Set<String> seen) {
        if (value instanceof Map<?, ?> map) {
            String accountId = first(map, "account_id", "accountId", "account-id"), clientId = first(map, "client_id", "clientId", "client-id"), secret = first(map, "client_secret", "clientSecret", "client-secret");
            if (!accountId.isBlank() && !clientId.isBlank() && !secret.isBlank() && seen.add(accountId + "\n" + clientId)) {
                String explicit = first(map, "name", "label", "trigram", "technical_account_name");
                String name = !explicit.isBlank() ? explicit : (suggested.isBlank() || isContainer(suggested) ? accountId : suggested);
                out.add(new TechnicalAccount(name, accountId, clientId, secret));
            }
            for (Map.Entry<?, ?> e : map.entrySet()) collectAccounts(e.getValue(), string(e.getKey()), out, seen);
        } else if (value instanceof Collection<?> c) for (Object nested : c) collectAccounts(nested, suggested, out, seen);
    }

    private static void collectEndpoints(Object value, List<OcsEndpoint> out, Set<String> seen) {
        if (value instanceof Map<?, ?> map) {
            String url = first(map, "ocs_servers_url", "ocsServersUrl", "servers_url", "serversUrl");
            if (!url.isBlank() && seen.add(url)) out.add(new OcsEndpoint(first(map, "region", "name", "location").isBlank() ? "default" : first(map, "region", "name", "location"), url));
            for (Object nested : map.values()) collectEndpoints(nested, out, seen);
        } else if (value instanceof Collection<?> c) for (Object nested : c) collectEndpoints(nested, out, seen);
    }

    private static DashboardSnapshot demoSnapshot() {
        List<AccountSummary> accounts = List.of(
                new AccountSummary("TRIG-DEV", "technical-account-dev", 54, 10, Map.of("paris", 34, "north", 20), Map.of("XLarge 8vCPU-16GB", 32, "Large-mem16 4vCPU-16GB", 16, "Medium 2vCPU-4GB", 6), ""),
                new AccountSummary("TRIG-INT", "technical-account-int", 42, 8, Map.of("paris", 26, "north", 16), Map.of("XLarge 8vCPU-16GB", 24, "Large-mem16 4vCPU-16GB", 12, "Medium 2vCPU-4GB", 6), ""),
                new AccountSummary("TRIG-PROD", "technical-account-prod", 86, 15, Map.of("paris", 54, "north", 32), Map.of("XLarge 8vCPU-16GB", 56, "Large-mem16 4vCPU-16GB", 24, "Medium 2vCPU-4GB", 6), "")
        );
        return new DashboardSnapshot("ready", "Demo inventory", Instant.now(), accounts);
    }

    private static void selfTest() throws Exception {
        String json = "{\"servers\":[{\"flavor\":{\"original_name\":\"XLarge 8vCPU-16GB\",\"vcpus\":99}},{\"flavor\":{\"original_name\":\"custom\",\"vcpus\":4}}]}";
        List<ServerFlavor> parsed = parseServers(json);
        if (parsed.size() != 2 || parsed.get(0).vcpus != 8 || parsed.get(1).vcpus != 4 || extractVcpus("Medium 2vCPU-4GB") != 2) throw new AssertionError("Parser test failed");
        System.out.println("Self-test passed");
    }

    private static Object vaultPayload(Object parsed) {
        if (!(parsed instanceof Map<?, ?> root)) return parsed;
        Object data = root.get("data");
        if (data instanceof Map<?, ?> first && (first.get("data") instanceof Map<?, ?> || first.get("data") instanceof List<?>)) return first.get("data");
        return data == null ? root : data;
    }
    private static List<String> vaultPaths(String path) { String p = path.replaceFirst("^/+", ""); return p.startsWith("v1/") ? List.of("/" + p) : List.of("/v1/secret/data/" + p, "/v1/secret/" + p); }
    private static boolean isContainer(String s) { return "accounts".equalsIgnoreCase(s) || "technical_accounts".equalsIgnoreCase(s) || "technicalAccounts".equalsIgnoreCase(s); }
    private static String first(Map<?, ?> map, String... keys) { for (String k : keys) { String s = string(map.get(k)); if (!s.isBlank()) return s; } return ""; }
    private static String findFirst(Object v, String... keys) { if (v instanceof Map<?, ?> m) { String d=first(m,keys); if(!d.isBlank())return d; for(Object n:m.values()){String f=findFirst(n,keys);if(!f.isBlank())return f;} } else if(v instanceof Collection<?> c) for(Object n:c){String f=findFirst(n,keys);if(!f.isBlank())return f;} return ""; }
    private static String findString(Object v, String key) { return findFirst(v, key); }
    private static long findNumber(Object v, String key) { if(v instanceof Map<?,?>m){Object d=m.get(key);if(d instanceof Number n)return n.longValue();for(Object n:m.values()){long x=findNumber(n,key);if(x>0)return x;}}else if(v instanceof Collection<?>c)for(Object n:c){long x=findNumber(n,key);if(x>0)return x;}return 0; }
    private static String string(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String stripSlash(String s) { return s.replaceFirst("/+$", ""); }
    private static String clean(Throwable e) { String s=e.getMessage(); return (s==null||s.isBlank())?e.getClass().getSimpleName():s; }
    private static String redactedBody(String body) {
        if (body == null) return "";
        String limited = body.length() > 4000 ? body.substring(0, 4000) + "...[truncated]" : body;
        return limited
                .replaceAll("(?i)(\"(?:access_token|client_token|client_secret|secret_id|password|token)\"\\s*:\\s*\")[^\"]*(\")", "$1[REDACTED]$2")
                .replaceAll("(?i)(Authorization\\s*[=:]\\s*)(Basic|Bearer)\\s+[^\\s,}]+", "$1$2 [REDACTED]");
    }
    private static long elapsedMs(long startedNanos) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos); }
    private static String blankLabel(String value) { return value == null || value.isBlank() ? "<none>" : value; }
    private static String safeUri(String value) {
        if (value == null || value.isBlank()) return "<missing>";
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null) return value.replaceAll("(?i)(token|secret|password)=[^&]+", "$1=[REDACTED]");
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (Exception e) { return "<invalid-url>"; }
    }
    private static String flavorSummary(List<ServerFlavor> servers) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ServerFlavor server : servers) counts.merge(server.originalName + "=" + server.vcpus + "vCPU", 1, Integer::sum);
        return counts.toString();
    }
    private static String esc(String s) { if(s==null)return ""; return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r"); }
    private static String value(String env, String property, String fallback) { String e=System.getenv(env); if(e!=null&&!e.isBlank())return e.trim(); String p=System.getProperty(property); return p!=null&&!p.isBlank()?p.trim():fallback; }
    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException { byte[] b=body.getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().set("Content-Type",contentType); ex.getResponseHeaders().set("Cache-Control","no-store"); ex.sendResponseHeaders(status,b.length); ex.getResponseBody().write(b); ex.close(); }

    record RuntimeConfig(boolean demoMode, String vaultUri, String namespace, String secretPath, String roleId, String secretId) {
        static RuntimeConfig load(){return new RuntimeConfig(Boolean.parseBoolean(value("DEMO_MODE","vcpu.dashboard.demo","false")),value("VAULT_URI","spring.cloud.vault.uri",""),value("VAULT_NAMESPACE","spring.cloud.vault.namespace",""),value("VAULT_TECH_ACCOUNTS_PATH","vcpu.dashboard.vault.path",value("VAULT_CONTEXT","spring.cloud.vault.kv.default-context","")),value("VAULT_ROLE_ID","spring.cloud.vault.app-role.role-id",""),value("VAULT_SECRET_ID","spring.cloud.vault.app-role.secret-id",""));}
        boolean complete(){return !vaultUri.isBlank()&&!secretPath.isBlank()&&!roleId.isBlank()&&!secretId.isBlank();}
    }
    record TechnicalAccount(String name,String accountId,String clientId,String clientSecret){}
    record OcsEndpoint(String region,String serversUrl){}
    record SecretConfig(String oauthUrl,List<TechnicalAccount> accounts,List<OcsEndpoint> endpoints){}
    record ServerFlavor(String originalName,int vcpus){}
    record AccountSummary(String name,String accountId,int vcpus,int servers,Map<String,Integer> regions,Map<String,Integer> flavors,String error){
        String toJson(){return "{\"name\":\""+esc(name)+"\",\"accountId\":\""+esc(accountId)+"\",\"vcpus\":"+vcpus+",\"servers\":"+servers+",\"regions\":"+mapJson(regions)+",\"flavors\":"+mapJson(flavors)+",\"error\":\""+esc(error)+"\"}";}
    }
    record DashboardSnapshot(String state,String message,Instant updatedAt,List<AccountSummary> accounts){
        static DashboardSnapshot loading(){return new DashboardSnapshot("loading","Starting…",Instant.now(),List.of());}
        DashboardSnapshot withState(String s,String m){return new DashboardSnapshot(s,m,updatedAt,accounts);}
        String toJson(){int total=accounts.stream().mapToInt(AccountSummary::vcpus).sum(),servers=accounts.stream().mapToInt(AccountSummary::servers).sum();return "{\"state\":\""+esc(state)+"\",\"message\":\""+esc(message)+"\",\"updatedAt\":\""+updatedAt+"\",\"totalVcpus\":"+total+",\"totalServers\":"+servers+",\"accounts\":["+accounts.stream().map(AccountSummary::toJson).reduce((a,b)->a+","+b).orElse("")+"]}";}
    }
    private static String mapJson(Map<String,Integer> map){StringBuilder b=new StringBuilder("{");int i=0;for(var e:map.entrySet()){if(i++>0)b.append(',');b.append('"').append(esc(e.getKey())).append("\":").append(e.getValue());}return b.append('}').toString();}

    static final class TraceLog {
        private static final long MAX_BYTES = 20L * 1024L * 1024L;
        private static final int BACKUPS = 5;
        private final Path path;

        TraceLog(Path path) { this.path = path.toAbsolutePath().normalize(); }

        void info(String category, String message) { write("INFO", category, message, null); }
        void error(String category, String message, Throwable error) { write("ERROR", category, message, error); }

        private synchronized void write(String level, String category, String message, Throwable error) {
            StringBuilder line = new StringBuilder().append(Instant.now()).append(" [").append(level).append("] [")
                    .append(category).append("] ").append(message == null ? "" : message);
            if (error != null) {
                line.append(System.lineSeparator()).append(error);
                StackTraceElement[] stack = error.getStackTrace();
                for (int i = 0; i < Math.min(stack.length, 30); i++) line.append(System.lineSeparator()).append("  at ").append(stack[i]);
            }
            String output = line.append(System.lineSeparator()).toString();
            System.out.print(output);
            try {
                Path parent = path.getParent();
                if (parent != null) Files.createDirectories(parent);
                rotateIfNeeded(output.getBytes(StandardCharsets.UTF_8).length);
                Files.writeString(path, output, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception logError) {
                System.err.println(Instant.now() + " [ERROR] [LOGGER] Unable to write " + path + ": " + clean(logError));
            }
        }

        private void rotateIfNeeded(int incomingBytes) throws IOException {
            if (!Files.exists(path) || Files.size(path) + incomingBytes <= MAX_BYTES) return;
            for (int i = BACKUPS; i >= 1; i--) {
                Path source = i == 1 ? path : path.resolveSibling(path.getFileName() + "." + (i - 1));
                Path target = path.resolveSibling(path.getFileName() + "." + i);
                if (Files.exists(source)) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    static final class JsonParser {
        private final String text; private int index;
        JsonParser(String text){this.text=text==null?"":text;}
        Object parse() throws IOException {Object v=value();ws();if(index!=text.length())throw new IOException("Unexpected JSON at "+index);return v;}
        private Object value() throws IOException {ws();if(index>=text.length())throw new IOException("Unexpected end of JSON");char c=text.charAt(index);return switch(c){case '{'->object();case '['->array();case '"'->string();case 't'->literal("true",true);case 'f'->literal("false",false);case 'n'->literal("null",null);default->{if(c=='-'||Character.isDigit(c))yield number();throw new IOException("Unexpected JSON character at "+index);}};}
        private Map<String,Object> object() throws IOException {expect('{');Map<String,Object>m=new LinkedHashMap<>();ws();if(peek('}')){index++;return m;}while(true){String k=string();expect(':');m.put(k,value());ws();if(peek('}')){index++;return m;}expect(',');}}
        private List<Object> array() throws IOException {expect('[');List<Object>l=new ArrayList<>();ws();if(peek(']')){index++;return l;}while(true){l.add(value());ws();if(peek(']')){index++;return l;}expect(',');}}
        private String string() throws IOException {expect('"');StringBuilder b=new StringBuilder();while(index<text.length()){char c=text.charAt(index++);if(c=='"')return b.toString();if(c=='\\'){if(index>=text.length())throw new IOException("Invalid escape");char e=text.charAt(index++);switch(e){case '"','\\','/'->b.append(e);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(index+4>text.length())throw new IOException("Invalid unicode escape");b.append((char)Integer.parseInt(text.substring(index,index+4),16));index+=4;}default->throw new IOException("Invalid escape");}}else b.append(c);}throw new IOException("Unterminated string");}
        private Object number() throws IOException {int s=index;if(peek('-'))index++;while(index<text.length()&&Character.isDigit(text.charAt(index)))index++;if(peek('.')){index++;while(index<text.length()&&Character.isDigit(text.charAt(index)))index++;}if(index<text.length()&&(text.charAt(index)=='e'||text.charAt(index)=='E')){index++;if(index<text.length()&&(text.charAt(index)=='+'||text.charAt(index)=='-'))index++;while(index<text.length()&&Character.isDigit(text.charAt(index)))index++;}String n=text.substring(s,index);try{return n.contains(".")||n.contains("e")||n.contains("E")?Double.parseDouble(n):Long.parseLong(n);}catch(NumberFormatException e){throw new IOException("Invalid number");}}
        private Object literal(String s,Object v)throws IOException{if(!text.startsWith(s,index))throw new IOException("Invalid literal");index+=s.length();return v;}
        private void expect(char c)throws IOException{ws();if(index>=text.length()||text.charAt(index)!=c)throw new IOException("Expected "+c+" at "+index);index++;}
        private boolean peek(char c){return index<text.length()&&text.charAt(index)==c;}
        private void ws(){while(index<text.length()&&Character.isWhitespace(text.charAt(index)))index++;}
    }

    static final class Html {
        static final String INDEX = """
<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Cloud Capacity · VCPU</title>
<style>
:root{--navy:#101b32;--blue:#3057e1;--sky:#eaf0ff;--ink:#17213a;--muted:#6d7892;--line:#e4e9f2;--white:#fff;--red:#c73c55}*{box-sizing:border-box}body{margin:0;background:#f5f7fb;color:var(--ink);font:15px/1.5 Inter,ui-sans-serif,system-ui,-apple-system,Segoe UI,sans-serif}header{background:var(--navy);color:white;padding:30px max(24px,calc((100vw - 1180px)/2));position:relative;overflow:hidden}header:after{content:"";position:absolute;width:420px;height:420px;border:1px solid #fff2;border-radius:50%;right:-100px;top:-260px;box-shadow:0 0 0 70px #ffffff08,0 0 0 140px #ffffff06}.eyebrow{text-transform:uppercase;letter-spacing:.16em;font-size:11px;color:#aebced;font-weight:700}.head{display:flex;align-items:end;justify-content:space-between;gap:20px;position:relative;z-index:1}h1{font-size:34px;line-height:1.1;margin:8px 0}.sub{color:#bac5e0}.refresh{border:1px solid #ffffff44;background:#ffffff12;color:white;border-radius:10px;padding:10px 16px;font-weight:700;cursor:pointer}.refresh:hover{background:#ffffff22}.wrap{max-width:1180px;margin:0 auto;padding:28px 24px 60px}.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin-bottom:24px}.metric{background:white;border:1px solid var(--line);border-radius:14px;padding:20px;box-shadow:0 8px 28px #23345d0a}.metric .label{font-size:12px;text-transform:uppercase;letter-spacing:.1em;color:var(--muted);font-weight:700}.metric .value{font-size:34px;font-weight:750;letter-spacing:-.04em;margin-top:6px}.metric.primary{background:var(--blue);color:white;border-color:var(--blue)}.metric.primary .label{color:#dbe2ff}.toolbar{display:flex;align-items:center;justify-content:space-between;margin:28px 0 12px}.toolbar h2{font-size:18px;margin:0}.stamp{font-size:13px;color:var(--muted)}.accounts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.card{background:white;border:1px solid var(--line);border-radius:14px;padding:20px;box-shadow:0 8px 28px #23345d08}.cardtop{display:flex;justify-content:space-between;gap:18px}.name{font-size:18px;font-weight:750}.id{font:12px ui-monospace,SFMono-Regular,Consolas;color:var(--muted);margin-top:2px}.vcpu{text-align:right}.vcpu b{display:block;font-size:30px;line-height:1;color:var(--blue)}.vcpu span{font-size:11px;text-transform:uppercase;letter-spacing:.1em;color:var(--muted)}.bar{height:7px;border-radius:99px;background:var(--sky);overflow:hidden;margin:18px 0}.bar i{height:100%;display:block;background:var(--blue);border-radius:inherit}.rows{display:grid;grid-template-columns:1fr 1fr;gap:18px}.section-title{font-size:11px;text-transform:uppercase;letter-spacing:.1em;color:var(--muted);font-weight:700;margin-bottom:8px}.row{display:flex;justify-content:space-between;border-top:1px solid #edf0f6;padding:7px 0;font-size:13px}.row b{font-variant-numeric:tabular-nums}.error{margin-top:12px;color:var(--red);background:#fff1f3;border-radius:8px;padding:8px 10px;font-size:12px}.empty{text-align:center;background:white;border:1px dashed #ccd4e3;border-radius:14px;padding:64px 20px;color:var(--muted);grid-column:1/-1}.pulse{display:inline-block;width:8px;height:8px;background:#ffbd48;border-radius:50%;margin-right:7px;animation:p 1.2s infinite}@keyframes p{50%{opacity:.3}}@media(max-width:760px){.metrics{grid-template-columns:1fr}.accounts{grid-template-columns:1fr}.head{align-items:start;flex-direction:column}h1{font-size:28px}.rows{grid-template-columns:1fr}}
</style></head><body><header><div class="head"><div><div class="eyebrow">Infrastructure inventory</div><h1>Cloud VCPU capacity</h1><div class="sub">Paris + North · grouped by technical account</div></div><button class="refresh" id="refresh">Refresh data</button></div></header><main class="wrap"><section class="metrics"><div class="metric primary"><div class="label">Total capacity</div><div class="value" id="total">—</div><div>VCPU across all accounts</div></div><div class="metric"><div class="label">Technical accounts</div><div class="value" id="accounts">—</div><div>Loaded securely from Vault</div></div><div class="metric"><div class="label">Servers inventoried</div><div class="value" id="servers">—</div><div>From configured OCS regions</div></div></section><div class="toolbar"><h2>Account allocation</h2><div class="stamp" id="stamp"><span class="pulse"></span>Connecting…</div></div><section class="accounts" id="grid"><div class="empty">Loading capacity data…</div></section></main>
<script>
const e=s=>document.querySelector(s),esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function rows(obj){return Object.entries(obj||{}).map(([k,v])=>`<div class="row"><span>${esc(k)}</span><b>${v} VCPU</b></div>`).join('')||'<div class="row"><span>No data</span><b>—</b></div>'}
function render(d){let waiting=!d.accounts.length&&(d.state==='loading'||d.state==='refreshing');e('#total').textContent=waiting?'—':d.totalVcpus.toLocaleString();e('#accounts').textContent=waiting?'—':d.accounts.length;e('#servers').textContent=waiting?'—':d.totalServers.toLocaleString();let max=Math.max(1,...d.accounts.map(a=>a.vcpus));e('#stamp').innerHTML=((d.state==='refreshing'||d.state==='loading')?'<span class="pulse"></span>':'')+esc(d.message)+' · '+new Date(d.updatedAt).toLocaleString();e('#grid').innerHTML=d.accounts.length?d.accounts.map(a=>`<article class="card"><div class="cardtop"><div><div class="name">${esc(a.name)}</div><div class="id">${esc(a.accountId)}</div></div><div class="vcpu"><b>${a.vcpus.toLocaleString()}</b><span>VCPU total</span></div></div><div class="bar"><i style="width:${a.vcpus/max*100}%"></i></div><div class="rows"><div><div class="section-title">By region · ${a.servers} servers</div>${rows(a.regions)}</div><div><div class="section-title">By flavor</div>${rows(a.flavors)}</div></div>${a.error?`<div class="error">${esc(a.error)}</div>`:''}</article>`).join(''):`<div class="empty">${esc(d.message)}</div>`;}
async function load(){try{render(await (await fetch('/api/dashboard',{cache:'no-store'})).json())}catch{x='#grid';e(x).innerHTML='<div class="empty">Dashboard API is unavailable.</div>'}}
e('#refresh').onclick=async()=>{e('#refresh').disabled=true;await fetch('/api/dashboard',{method:'POST'});await load();setTimeout(load,1300);e('#refresh').disabled=false};load();setInterval(load,10000);
</script></body></html>""";
    }
}
