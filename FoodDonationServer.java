import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class FoodDonationServer {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final Path ACCOUNTS_FILE = Paths.get("accounts.txt");
    private static final List<Donation> donations = new ArrayList<>();
    private static final Map<String, UserAccount> accounts = new HashMap<>();

    public static void main(String[] args) throws IOException {
        loadAccounts();
        seedDonations();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        server.createContext("/", FoodDonationServer::handleRequest);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("ShareTable is running at http://localhost:" + PORT);
        System.out.println("Register your own Donor or Consumer account at http://localhost:" + PORT);
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        Map<String, String> params = parseParams(exchange.getRequestURI().getRawQuery());

        if ("GET".equals(method) && "/".equals(path)) {
            sendHtml(exchange, loginPage(params.get("error")));
            return;
        }
        if ("POST".equals(method) && "/login".equals(path)) {
            Map<String, String> form = parseBody(exchange);
            String role = form.getOrDefault("role", "consumer");
            String username = form.getOrDefault("username", "");
            String password = form.getOrDefault("password", "");
            if (validLogin(role, username, password)) {
                exchange.getResponseHeaders().add("Set-Cookie", "role=" + role + "; Path=/; HttpOnly");
                redirect(exchange, "/dashboard");
            } else {
                redirect(exchange, "/?error=" + encode("Username or password is incorrect."));
            }
            return;
        }
        if ("GET".equals(method) && "/register".equals(path)) {
            sendHtml(exchange, registerPage(params.get("error")));
            return;
        }
        if ("POST".equals(method) && "/register".equals(path)) {
            Map<String, String> form = parseBody(exchange);
            String role = form.getOrDefault("role", "consumer");
            String username = clean(form.getOrDefault("username", "")).toLowerCase();
            String password = form.getOrDefault("password", "");
            String result = registerAccount(role, username, password);
            if (result.isEmpty()) {
                exchange.getResponseHeaders().add("Set-Cookie", "role=" + role + "; Path=/; HttpOnly");
                redirect(exchange, "/dashboard");
            } else {
                redirect(exchange, "/register?error=" + encode(result));
            }
            return;
        }
        if ("GET".equals(method) && "/logout".equals(path)) {
            exchange.getResponseHeaders().add("Set-Cookie", "role=; Max-Age=0; Path=/");
            redirect(exchange, "/");
            return;
        }

        String role = cookieValue(exchange, "role");
        if (role.isEmpty()) {
            redirect(exchange, "/");
            return;
        }
        if ("POST".equals(method) && "/donate".equals(path) && "donor".equals(role)) {
            addDonation(parseBody(exchange));
            redirect(exchange, "/dashboard?view=donor&saved=1");
            return;
        }
        if ("POST".equals(method) && "/claim".equals(path) && "consumer".equals(role)) {
            updateStatus(params.get("id"), "Claim requested");
            redirect(exchange, "/dashboard?view=consumer&claimed=1");
            return;
        }
        if ("POST".equals(method) && "/update".equals(path) && "admin".equals(role)) {
            updateStatus(params.get("id"), params.getOrDefault("status", "Available"));
            redirect(exchange, "/dashboard?view=admin&updated=1");
            return;
        }
        if ("GET".equals(method) && "/dashboard".equals(path)) {
            String selectedView = params.getOrDefault("view", role);
            if (!selectedView.equals(role)) {
                selectedView = role;
            }
            sendHtml(exchange, dashboardPage(role, selectedView, params));
            return;
        }
        sendHtml(exchange, page("Not found", "<div class='empty'><h2>Page not found</h2><a class='button' href='/dashboard'>Back to dashboard</a></div>"), 404);
    }

    private static boolean validLogin(String role, String username, String password) {
        if ("admin".equals(role)) {
            return "admin".equals(username) && "admin123".equals(password);
        }
        synchronized (accounts) {
            UserAccount account = accounts.get(username);
            return account != null && account.role.equals(role) && account.password.equals(password);
        }
    }

    private static String registerAccount(String role, String username, String password) {
        if (!"donor".equals(role) && !"consumer".equals(role)) return "Choose Donor or Consumer as your role.";
        if (!username.matches("[a-z0-9._-]{3,24}")) return "Username must be 3-24 characters using letters, numbers, dots, _ or -.";
        if ("admin".equals(username)) return "That username is reserved.";
        if (password.length() < 6) return "Password must contain at least 6 characters.";
        synchronized (accounts) {
            if (accounts.containsKey(username)) return "That username is already registered.";
            accounts.put(username, new UserAccount(role, password));
            saveAccounts();
        }
        return "";
    }

    private static void loadAccounts() {
        if (!Files.exists(ACCOUNTS_FILE)) return;
        try {
            for (String line : Files.readAllLines(ACCOUNTS_FILE, StandardCharsets.UTF_8)) {
                String[] values = line.split("\\|", 3);
                if (values.length == 3) accounts.put(values[0], new UserAccount(values[1], values[2]));
            }
        } catch (IOException ignored) {
            System.err.println("Could not load accounts.txt; starting with no registered users.");
        }
    }

    private static void saveAccounts() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, UserAccount> entry : accounts.entrySet()) {
            lines.add(entry.getKey() + "|" + entry.getValue().role + "|" + entry.getValue().password);
        }
        try {
            Files.write(ACCOUNTS_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            System.err.println("Could not save accounts.txt.");
        }
    }

    private static void addDonation(Map<String, String> form) {
        String food = clean(form.getOrDefault("food", "Mixed food"));
        String quantity = clean(form.getOrDefault("quantity", "1 box"));
        String pickup = clean(form.getOrDefault("pickup", "Community center"));
        synchronized (donations) {
            donations.add(new Donation(donations.size() + 1, food, quantity, pickup, "Today", "Available"));
        }
    }

    private static void updateStatus(String id, String status) {
        if (id == null) return;
        synchronized (donations) {
            for (Donation donation : donations) {
                if (String.valueOf(donation.id).equals(id)) {
                    donation.status = clean(status);
                }
            }
        }
    }

    private static String dashboardPage(String role, String view, Map<String, String> params) {
        int available = count("Available");
        int claimed = count("Claim requested") + count("Collected");
        int totalMeals = 0;
        synchronized (donations) {
            for (Donation donation : donations) {
                totalMeals += parseQuantity(donation.quantity);
            }
        }
        String content = "admin".equals(view) ? adminPanel() : "donor".equals(view) ? donorPanel(params) : consumerPanel(params);
        String roleTitle = title(role) + " panel";
        String nav = "<a class='nav-item " + active(role, "admin") + "' href='/dashboard?view=admin'><span>O</span> Overview</a>"
            + "<a class='nav-item " + active(role, "donor") + "' href='/dashboard?view=donor'><span>+</span> Donate food</a>"
            + "<a class='nav-item " + active(role, "consumer") + "' href='/dashboard?view=consumer'><span>H</span> Find food</a>";
        String stats = "<section class='stats'>"
                + stat("Meals shared", String.valueOf(totalMeals), "Across all donations", "mint")
                + stat("Available now", String.valueOf(available), "Ready for pickup", "peach")
                + stat("Requests", String.valueOf(claimed), "In progress", "blue")
                + "</section>";
        String body = "<div class='shell'><aside class='sidebar'><div class='brand'><span class='brand-mark'>S</span><span>ShareTable</span></div>"
                + "<div class='side-label'>Workspace</div>" + nav + "<div class='side-bottom'><div class='profile'><span class='avatar'>" + title(role).charAt(0) + "</span><div><b>" + title(role) + " account</b><small>Active today</small></div></div><a class='logout' href='/logout'>Sign out</a></div></aside>"
                + "<main class='main'><header class='topbar'><div><p class='eyebrow'>Good food, shared well</p><h1>" + roleTitle + "</h1></div><div class='top-date'>" + LocalDate.now() + "<span class='live-dot'></span></div></header>" + stats + content + "</main></div>";
        return page("ShareTable | " + roleTitle, body);
    }

    private static String adminPanel() {
        StringBuilder rows = new StringBuilder();
        synchronized (donations) {
            for (Donation donation : donations) {
                rows.append("<tr><td><b>#" + donation.id + "</b></td><td><strong>" + esc(donation.food) + "</strong><small>" + esc(donation.quantity) + "</small></td><td>" + esc(donation.pickup) + "</td><td>" + badge(donation.status) + "</td><td><form class='inline-form' method='post' action='/update?id=" + donation.id + "'><select name='status'><option>Available</option><option>Claim requested</option><option>Collected</option></select><button class='small-button' type='submit'>Update</button></form></td></tr>");
            }
        }
        return "<div class='section-heading'><div><p class='eyebrow'>Operations</p><h2>Donation activity</h2></div><span class='count-pill'>" + donations.size() + " total records</span></div>"
                + "<section class='table-card'><table><thead><tr><th>ID</th><th>Donation</th><th>Pickup point</th><th>Status</th><th>Manage</th></tr></thead><tbody>" + rows + "</tbody></table></section>"
                + accountDirectory();
    }

    private static String accountDirectory() {
        StringBuilder rows = new StringBuilder();
        int donors = 0;
        int consumers = 0;
        synchronized (accounts) {
            for (Map.Entry<String, UserAccount> entry : accounts.entrySet()) {
                UserAccount account = entry.getValue();
                if ("donor".equals(account.role)) donors++;
                if ("consumer".equals(account.role)) consumers++;
                rows.append("<tr><td><span class='avatar small-avatar'>" + title(account.role).charAt(0) + "</span></td><td><strong>" + esc(entry.getKey()) + "</strong><small>Registered account</small></td><td>" + title(account.role) + "</td><td><span class='badge'>Password protected</span></td></tr>");
            }
        }
        if (rows.length() == 0) {
            rows.append("<tr><td colspan='4'><div class='empty'>No Donor or Consumer accounts have registered yet.</div></td></tr>");
        }
        return "<div class='section-heading compact'><div><p class='eyebrow'>People</p><h2>Donor and Consumer directory</h2><p class='section-copy'>" + donors + " donors and " + consumers + " consumers registered.</p></div></div>"
                + "<section class='table-card'><table><thead><tr><th></th><th>Username</th><th>Account type</th><th>Security</th></tr></thead><tbody>" + rows + "</tbody></table></section>";
    }

    private static String donorPanel(Map<String, String> params) {
        String notice = params.containsKey("saved") ? "<div class='notice success'>Your donation is now visible to consumers.</div>" : "";
        return notice + "<div class='section-heading'><div><p class='eyebrow'>Make an impact</p><h2>Share surplus food</h2><p class='section-copy'>Tell local communities what you have available today.</p></div></div>"
                + "<section class='form-card'><form method='post' action='/donate'><div class='form-grid'><label>Food available<input name='food' required placeholder='e.g. Vegetable biryani'></label><label>Quantity<input name='quantity' required placeholder='e.g. 25 meals'></label><label class='wide'>Pickup location<input name='pickup' required placeholder='e.g. 14 Market Street'></label></div><button class='button' type='submit'>Publish donation <span>→</span></button></form></section>"
                + "<div class='section-heading compact'><div><p class='eyebrow'>Your activity</p><h2>Recent donations</h2></div></div>" + donationCards("donor");
    }

    private static String consumerPanel(Map<String, String> params) {
        String notice = params.containsKey("claimed") ? "<div class='notice success'>Request sent. The donor will prepare your pickup.</div>" : "";
        return notice + "<div class='section-heading'><div><p class='eyebrow'>Community shelf</p><h2>Food available near you</h2><p class='section-copy'>Reserve what you need and help prevent good food from going to waste.</p></div><span class='count-pill'>" + count("Available") + " available</span></div>" + donationCards("consumer");
    }

    private static String donationCards(String panel) {
        StringBuilder cards = new StringBuilder("<section class='donation-grid'>");
        synchronized (donations) {
            for (Donation donation : donations) {
                if ("donor".equals(panel) || "Available".equals(donation.status)) {
                    String action = "consumer".equals(panel) && "Available".equals(donation.status)
                            ? "<form method='post' action='/claim?id=" + donation.id + "'><button class='small-button' type='submit'>Request pickup</button></form>"
                            : "<span class='card-status'>" + badge(donation.status) + "</span>";
                    cards.append("<article class='donation-card'><div class='food-icon'>" + foodIcon(donation.food) + "</div><div class='card-main'><div class='card-top'><span class='category'>DONATION #" + donation.id + "</span>" + badge(donation.status) + "</div><h3>" + esc(donation.food) + "</h3><p class='quantity'>" + esc(donation.quantity) + " <span>•</span> " + esc(donation.pickup) + "</p><div class='card-bottom'><small>Posted " + donation.date + "</small>" + action + "</div></div></article>");
                }
            }
        }
        if (cards.toString().equals("<section class='donation-grid'>")) {
            cards.append("<div class='empty'><h3>No donations yet</h3><p>New food listings will appear here.</p></div>");
        }
        return cards.append("</section>").toString();
    }

    private static String loginPage(String error) {
        String notice = error == null ? "" : "<div class='notice error'>" + esc(error) + "</div>";
        String body = "<div class='login-layout'><div class='login-art'><div class='brand light'><span class='brand-mark'>S</span><span>ShareTable</span></div><div class='art-copy'><p class='eyebrow light-text'>A little extra can go a long way</p><h1>Good food belongs<br>on every table.</h1><p>Coordinate surplus food, local donors, and people who need a helping hand.</p></div><div class='art-footer'><span>♧</span> Building kinder communities, one meal at a time</div></div><div class='login-side'><div class='login-box'><p class='eyebrow'>Welcome to ShareTable</p><h2>Sign in to your workspace</h2><p class='login-subtitle'>Use your own registered username and password.</p>" + notice + "<form method='post' action='/login'><label>Workspace role<select name='role'><option value='consumer'>Consumer</option><option value='donor'>Donor</option><option value='admin'>Admin</option></select></label><label>Username<input name='username' required placeholder='Enter username'></label><label>Password<input name='password' type='password' required placeholder='Enter password'></label><button class='button full' type='submit'>Continue <span>→</span></button></form><p class='account-link'>New here? <a href='/register'>Create your own account</a></p><div class='demo-box'><b>Admin access</b><span>admin / admin123</span></div></div></div></div>";
        return page("ShareTable | Sign in", body);
    }

    private static String registerPage(String error) {
        String notice = error == null ? "" : "<div class='notice error'>" + esc(error) + "</div>";
        String body = "<div class='login-layout'><div class='login-art'><div class='brand light'><span class='brand-mark'>S</span><span>ShareTable</span></div><div class='art-copy'><p class='eyebrow light-text'>Join the table</p><h1>Turn extra food<br>into shared good.</h1><p>Create an account to donate surplus meals or request food from your local community.</p></div><div class='art-footer'>Your account is saved locally on this computer.</div></div><div class='login-side'><div class='login-box'><p class='eyebrow'>Create your account</p><h2>Start sharing today</h2><p class='login-subtitle'>Choose how you want to help the community.</p>" + notice + "<form method='post' action='/register'><label>Account type<select name='role'><option value='consumer'>Consumer - request food</option><option value='donor'>Donor - share food</option></select></label><label>Username<input name='username' required minlength='3' maxlength='24' placeholder='Choose a username'></label><label>Password<input name='password' type='password' required minlength='6' placeholder='At least 6 characters'></label><button class='button full' type='submit'>Create account <span>→</span></button></form><p class='account-link'>Already registered? <a href='/'>Sign in here</a></p></div></div></div>";
        return page("ShareTable | Create account", body);
    }

    private static String page(String title, String body) {
        return "<!doctype html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>" + title + "</title><style>" + styles() + "</style></head><body>" + body + "</body></html>";
    }

    private static String styles() {
        return "*{box-sizing:border-box}body{margin:0;background:#fbfdfb;color:#193d38;font:14px Arial,sans-serif}a{color:inherit;text-decoration:none}button,input,select{font:inherit}.shell{display:flex;min-height:100vh}.sidebar{width:240px;background:#174b43;color:white;padding:28px 20px;display:flex;flex-direction:column}.brand{display:flex;align-items:center;gap:10px;font-size:20px;font-weight:bold}.brand-mark{width:31px;height:31px;border-radius:9px;background:#ee805f;color:white;display:grid;place-items:center;font-size:20px}.side-label{color:#9cc1b7;text-transform:uppercase;font-size:10px;letter-spacing:1px;margin:65px 12px 12px}.nav-item{color:#b8d5ce;border-radius:8px;padding:12px;display:flex;gap:12px;margin:3px 0}.nav-item:hover,.nav-item.active{background:#28665b;color:white}.side-bottom{margin-top:auto}.profile{border-top:1px solid #397064;padding:18px 4px;display:flex;align-items:center;gap:9px}.avatar{background:#e9b58c;color:#174b43;width:32px;height:32px;display:grid;place-items:center;border-radius:50%;font-weight:bold}.profile b,.profile small{display:block}.profile b{font-size:12px}.profile small{color:#9cc1b7;font-size:11px;margin-top:3px}.logout{display:block;color:#b8d5ce;padding:8px;font-size:12px}.main{max-width:1240px;flex:1;padding:42px 7%;overflow:hidden}.topbar{display:flex;justify-content:space-between;margin-bottom:30px}.eyebrow{font-size:10px;text-transform:uppercase;letter-spacing:1.4px;color:#ee805f;font-weight:bold;margin:0 0 8px}.topbar h1{font-size:32px;margin:0}.top-date{color:#78908a;font-size:12px}.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:15px;margin-bottom:42px}.stat{padding:22px 24px;border-radius:12px}.stat.mint{background:#e2f3eb}.stat.peach{background:#fff0e6}.stat.blue{background:#e9f1f5}.stat-label,.stat-note{color:#78908a;font-size:12px}.stat-number{display:block;font-size:31px;font-weight:bold;margin:7px 0 2px}.section-heading{display:flex;justify-content:space-between;align-items:end;margin-bottom:18px}.section-heading h2{font-size:24px;margin:0}.section-copy{color:#78908a;margin:8px 0 0}.count-pill{background:#edf5f1;color:#197d70;font-size:11px;font-weight:bold;padding:8px 12px;border-radius:20px}.table-card,.form-card{background:white;border:1px solid #e5eeeb;border-radius:12px;overflow:auto}.table-card{padding:4px}table{border-collapse:collapse;width:100%;min-width:720px}th{text-align:left;color:#78908a;font-size:10px;text-transform:uppercase;letter-spacing:1px;padding:15px}td{padding:16px 15px;border-top:1px solid #e5eeeb;color:#55736b}td strong,td small{display:block}td strong{color:#193d38}td small{font-size:11px;margin-top:4px;color:#78908a}.badge{display:inline-block;border-radius:20px;padding:6px 9px;background:#eff7f2;color:#398367;font-size:10px;font-weight:bold;white-space:nowrap}.badge.claim-requested{background:#fff2e9;color:#cb704a}.badge.collected{background:#e9f1f5;color:#577c91}.inline-form{display:flex;gap:6px}.inline-form select{border:1px solid #e5eeeb;color:#78908a;font-size:11px;border-radius:5px;padding:5px}.small-button,.button{background:#197d70;border:0;color:white;border-radius:7px;padding:8px 11px;font-weight:bold;cursor:pointer}.button{padding:13px 18px}.button span{font-size:18px;margin-left:12px}.form-card{padding:28px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-bottom:24px}.form-grid .wide{grid-column:1/-1}label{display:block;color:#193d38;font-size:12px;font-weight:bold}input,select{display:block;width:100%;border:1px solid #dce9e4;border-radius:7px;background:#fbfdfb;padding:12px 13px;margin-top:8px;color:#193d38}.donation-grid{display:grid;grid-template-columns:repeat(2,minmax(280px,1fr));gap:16px}.donation-card{background:white;border:1px solid #e5eeeb;border-radius:12px;padding:18px;display:flex;gap:15px}.food-icon{background:#fff0e6;width:54px;height:54px;border-radius:11px;display:grid;place-items:center;font-size:24px}.card-main{flex:1}.card-top,.card-bottom{display:flex;justify-content:space-between;align-items:center;gap:8px}.category{color:#ee805f;font-size:9px;font-weight:bold;letter-spacing:1px}.donation-card h3{font-size:18px;margin:12px 0 5px}.quantity{color:#78908a;font-size:12px;margin:0}.card-bottom{border-top:1px solid #e5eeeb;margin-top:20px;padding-top:14px}.card-bottom small{color:#78908a;font-size:10px}.notice{border-radius:8px;padding:12px 15px;margin-bottom:22px;font-size:12px}.notice.success{background:#e6f5ed;color:#398367}.notice.error{background:#fff0ea;color:#c76848}.empty{padding:38px;text-align:center;border:1px dashed #d4e5df;color:#78908a;grid-column:1/-1}.login-layout{min-height:100vh;display:grid;grid-template-columns:46% 54%;background:#fbfdfb}.login-art{background:#174b43;padding:34px 8%;color:white;display:flex;flex-direction:column}.light{color:white}.art-copy{margin:auto 0}.art-copy h1{font-size:52px;line-height:1.06;margin:12px 0 22px}.art-copy p:not(.eyebrow){color:#c7e0d9;max-width:390px;line-height:1.7}.light-text{color:#f3af91}.art-footer{color:#a9cfc3;font-size:11px}.login-side{display:grid;place-items:center;padding:30px}.login-box{width:min(400px,100%)}.login-box h2{font-size:32px;margin:0 0 8px}.login-subtitle{color:#78908a;margin:0 0 28px;line-height:1.5}.login-box form label{margin-top:17px}.button.full{width:100%;margin-top:25px}.demo-box{background:#f0f7f3;border-radius:8px;padding:14px 16px;margin-top:28px;color:#78908a;font-size:11px}.demo-box b{color:#193d38;display:block;margin-bottom:7px}.demo-box span{margin-right:13px}@media(max-width:800px){.sidebar{width:200px}.main{padding:30px}.login-layout{grid-template-columns:1fr}.login-art{min-height:260px}.donation-grid{grid-template-columns:1fr}}@media(max-width:580px){.sidebar{width:66px;padding:20px 10px}.sidebar .brand span:last-child,.side-label,.nav-item:not(:first-of-type),.profile div,.logout{display:none}.nav-item{justify-content:center}.main{padding:25px 18px}.stats{grid-template-columns:1fr}.form-grid{grid-template-columns:1fr}.form-grid .wide{grid-column:auto}}";
    }

    private static void legacyStyles() {
        /*
        return "@import url('https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&family=Playfair+Display:wght@600;700&display=swap');:root{--ink:#193d38;--muted:#78908a;--teal:#197d70;--coral:#ee805f;--line:#e5eeeb;--paper:#fbfdfb;--mint:#e2f3eb;--peach:#fff0e6;--blue:#e9f1f5}*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:14px 'DM Sans',sans-serif}a{color:inherit;text-decoration:none}button,input,select{font:inherit}.shell{display:flex;min-height:100vh}.sidebar{width:250px;background:#174b43;color:white;padding:30px 22px 24px;display:flex;flex-direction:column}.brand{display:flex;align-items:center;gap:10px;font-size:20px;font-weight:700;letter-spacing:-.5px}.brand-mark{width:31px;height:31px;border-radius:9px;background:var(--coral);color:white;display:grid;place-items:center;font-family:Georgia,serif;font-size:20px}.side-label{color:#9cc1b7;text-transform:uppercase;font-size:10px;font-weight:700;letter-spacing:1.4px;margin:68px 14px 14px}.nav-item{color:#b8d5ce;border-radius:9px;padding:13px 14px;display:flex;gap:13px;align-items:center;margin:3px 0;font-weight:500}.nav-item span{font-size:19px;width:20px;text-align:center}.nav-item:hover,.nav-item:first-of-type{background:#28665b;color:white}.nav-item:nth-of-type(2),.nav-item:nth-of-type(3){background:transparent}.side-bottom{margin-top:auto}.profile{border-top:1px solid #397064;padding:18px 8px;display:flex;align-items:center;gap:10px}.avatar{background:#e9b58c;color:#174b43;width:32px;height:32px;display:grid;place-items:center;border-radius:50%;font-weight:700}.profile b,.profile small{display:block}.profile b{font-size:12px}.profile small{color:#9cc1b7;font-size:11px;margin-top:3px}.logout{display:block;color:#b8d5ce;padding:8px;font-size:12px}.main{max-width:1240px;flex:1;padding:42px 7%;overflow:hidden}.topbar{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:30px}.eyebrow{font-size:10px;text-transform:uppercase;letter-spacing:1.5px;color:var(--coral);font-weight:700;margin:0 0 8px}.topbar h1{font:700 32px 'Playfair Display',Georgia,serif;margin:0;letter-spacing:-.5px}.top-date{color:var(--muted);font-size:12px;padding-top:9px}.live-dot{width:7px;height:7px;background:#53b784;border-radius:50%;display:inline-block;margin-left:8px}.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:15px;margin-bottom:45px}.stat{padding:22px 24px;border-radius:12px}.stat.mint{background:var(--mint)}.stat.peach{background:var(--peach)}.stat.blue{background:var(--blue)}.stat-label{color:var(--muted);font-size:12px}.stat-number{font:700 31px 'Playfair Display',Georgia,serif;display:block;margin:7px 0 2px}.stat-note{font-size:11px;color:var(--muted)}.section-heading{display:flex;justify-content:space-between;align-items:end;margin-bottom:18px}.section-heading h2{font:700 24px 'Playfair Display',Georgia,serif;margin:0}.section-copy{color:var(--muted);margin:8px 0 0}.section-heading.compact{margin-top:45px}.count-pill{background:#edf5f1;color:var(--teal);font-size:11px;font-weight:700;padding:8px 12px;border-radius:20px}.table-card,.form-card{background:white;border:1px solid var(--line);border-radius:13px;box-shadow:0 7px 25px #174b4308;overflow:auto}.table-card{padding:4px}table{border-collapse:collapse;width:100%;min-width:720px}th{text-align:left;color:var(--muted);font-size:10px;text-transform:uppercase;letter-spacing:1px;padding:15px}td{padding:16px 15px;border-top:1px solid var(--line);color:#55736b}td strong,td small{display:block}td strong{color:var(--ink)}td small{font-size:11px;margin-top:4px;color:var(--muted)}.badge{display:inline-block;border-radius:20px;padding:6px 9px;background:#eff7f2;color:#398367;font-size:10px;font-weight:700;white-space:nowrap}.badge.claim-requested{background:#fff2e9;color:#cb704a}.badge.collected{background:#e9f1f5;color:#577c91}.inline-form{display:flex;gap:6px}.inline-form select{border:1px solid var(--line);color:var(--muted);font-size:11px;border-radius:5px;padding:5px}.small-button{background:var(--teal);border:0;color:white;border-radius:6px;padding:7px 10px;font-size:11px;font-weight:700;cursor:pointer}.form-card{padding:28px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-bottom:24px}.form-grid .wide{grid-column:1/-1}label{display:block;color:var(--ink);font-size:12px;font-weight:700}input,select{display:block;width:100%;border:1px solid #dce9e4;border-radius:7px;background:#fbfdfb;padding:12px 13px;margin-top:8px;color:var(--ink);outline:0}input:focus,select:focus{border-color:var(--teal)}.button{background:var(--teal);border:0;color:white;border-radius:7px;padding:13px 18px;font-weight:700;cursor:pointer}.button span{font-size:18px;margin-left:12px}.button.full{width:100%;margin-top:25px}.donation-grid{display:grid;grid-template-columns:repeat(2,minmax(280px,1fr));gap:16px}.donation-card{background:white;border:1px solid var(--line);border-radius:13px;padding:18px;display:flex;gap:15px}.food-icon{background:var(--peach);width:54px;height:54px;border-radius:11px;display:grid;place-items:center;font-size:26px;flex:none}.card-main{flex:1;min-width:0}.card-top,.card-bottom{display:flex;justify-content:space-between;align-items:center;gap:8px}.category{color:var(--coral);font-size:9px;font-weight:700;letter-spacing:1px}.donation-card h3{font:700 18px 'Playfair Display',Georgia,serif;margin:12px 0 5px}.quantity{color:var(--muted);font-size:12px;margin:0}.quantity span{color:#c2d4cd;padding:0 4px}.card-bottom{border-top:1px solid var(--line);margin-top:20px;padding-top:14px}.card-bottom small{color:var(--muted);font-size:10px}.card-status{font-size:10px}.notice{border-radius:8px;padding:12px 15px;margin-bottom:22px;font-size:12px}.notice.success{background:#e6f5ed;color:#398367}.notice.error{background:#fff0ea;color:#c76848}.empty{padding:38px;text-align:center;border:1px dashed #d4e5df;color:var(--muted);grid-column:1/-1}.empty h3{color:var(--ink);margin:0 0 5px}.empty p{margin:0}.login-layout{min-height:100vh;display:grid;grid-template-columns:46% 54%;background:var(--paper)}.login-art{background:linear-gradient(145deg,#174b43,#286b5d);padding:34px 8%;color:white;display:flex;flex-direction:column}.light{color:white}.art-copy{margin:auto 0}.art-copy h1{font:700 clamp(38px,4vw,60px) 'Playfair Display',Georgia,serif;line-height:1.06;letter-spacing:-1.5px;margin:12px 0 22px}.art-copy p:not(.eyebrow){color:#c7e0d9;max-width:390px;line-height:1.7}.light-text{color:#f3af91}.art-footer{color:#a9cfc3;font-size:11px}.art-footer span{color:var(--coral);font-size:23px;vertical-align:middle;margin-right:8px}.login-side{display:grid;place-items:center;padding:30px}.login-box{width:min(400px,100%)}.login-box h2{font:700 32px 'Playfair Display',Georgia,serif;margin:0 0 8px}.login-subtitle{color:var(--muted);margin:0 0 28px;line-height:1.5}.login-box form label{margin-top:17px}.demo-box{background:#f0f7f3;border-radius:8px;padding:14px 16px;margin-top:28px;color:var(--muted);font-size:11px}.demo-box b{color:var(--ink);display:block;margin-bottom:7px}.demo-box span{margin-right:13px}.login-box .notice{margin-bottom:4px}@media(max-width:800px){.sidebar{width:200px}.main{padding:30px}.login-layout{grid-template-columns:1fr}.login-art{min-height:260px;padding:25px 8%}.art-copy{margin:45px 0 20px}.art-copy h1{font-size:38px}.stats{gap:8px}.donation-grid{grid-template-columns:1fr}}@media(max-width:580px){.sidebar{width:66px;padding:20px 10px}.sidebar .brand span:last-child,.side-label,.nav-item:not(:first-of-type),.profile div,.logout{display:none}.nav-item{padding:12px;justify-content:center}.main{padding:25px 18px}.topbar h1{font-size:26px}.stats{grid-template-columns:1fr}.stats{margin-bottom:30px}.form-grid{grid-template-columns:1fr}.form-grid .wide{grid-column:auto}.donation-grid{grid-template-columns:1fr}.top-date{display:none}}");
        */
    }

    private static String stat(String label, String number, String note, String color) {
        return "<div class='stat " + color + "'><span class='stat-label'>" + label + "</span><span class='stat-number'>" + number + "</span><span class='stat-note'>" + note + "</span></div>";
    }

    private static String badge(String status) {
        return "<span class='badge " + status.toLowerCase().replace(' ', '-') + "'>" + esc(status) + "</span>";
    }

    private static String foodIcon(String food) {
        String lower = food.toLowerCase();
        if (lower.contains("fruit") || lower.contains("apple")) return "FR";
        if (lower.contains("bread") || lower.contains("bakery")) return "BK";
        if (lower.contains("rice") || lower.contains("biryani")) return "RC";
            return "FD";
    }

    private static int count(String status) {
        int result = 0;
        synchronized (donations) {
            for (Donation donation : donations) if (status.equals(donation.status)) result++;
        }
        return result;
    }

    private static int parseQuantity(String quantity) {
        try {
            return Integer.parseInt(quantity.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return 1;
        }
    }

    private static void seedDonations() {
        donations.add(new Donation(1, "Vegetable biryani", "24 meals", "Greenway Community Hall", "Today", "Available"));
        donations.add(new Donation(2, "Fresh bakery boxes", "12 boxes", "Northside Food Bank", "Today", "Available"));
        donations.add(new Donation(3, "Seasonal fruit", "18 bags", "Riverside Shelter", "Yesterday", "Claim requested"));
    }

    private static String active(String role, String link) {
        return role.equals(link) ? "active" : "";
    }

    private static String title(String value) {
        return value.substring(0, 1).toUpperCase() + value.substring(1);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("[<>]", "");
    }

    private static String esc(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parseParams(body);
    }

    private static Map<String, String> parseParams(String input) {
        Map<String, String> values = new HashMap<>();
        if (input == null || input.isEmpty()) return values;
        for (String pair : input.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private static String cookieValue(HttpExchange exchange, String key) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return "";
        for (String part : cookie.split(";")) {
            String[] values = part.trim().split("=", 2);
            if (values.length == 2 && key.equals(values[0])) return values[1];
        }
        return "";
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static void sendHtml(HttpExchange exchange, String html) throws IOException {
        sendHtml(exchange, html, 200);
    }

    private static void sendHtml(HttpExchange exchange, String html, int status) throws IOException {
        byte[] content = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static class Donation {
        private final int id;
        private final String food;
        private final String quantity;
        private final String pickup;
        private final String date;
        private String status;

        private Donation(int id, String food, String quantity, String pickup, String date, String status) {
            this.id = id;
            this.food = food;
            this.quantity = quantity;
            this.pickup = pickup;
            this.date = date;
            this.status = status;
        }
    }

    private static class UserAccount {
        private final String role;
        private final String password;

        private UserAccount(String role, String password) {
            this.role = role;
            this.password = password;
        }
    }
}
