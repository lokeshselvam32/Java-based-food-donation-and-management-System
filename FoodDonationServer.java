import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
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
    private static final String ADMIN_USERNAME = System.getenv().getOrDefault("ADMIN_USERNAME", "admin");
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ADMIN_PASSWORD", "admin123");
    private static final Path ACCOUNTS_FILE = Paths.get("accounts.txt");
    private static final Path MESSAGES_FILE = Paths.get("messages.txt");
    private static final List<Donation> donations = new ArrayList<>();
    private static final List<Message> messages = new ArrayList<>();
    private static final Map<String, UserAccount> accounts = new HashMap<>();

    public static void main(String[] args) throws IOException {
        loadAccounts();
        loadMessages();
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

        if ("GET".equals(method) && "/logo.svg".equals(path)) {
            sendSvg(exchange);
            return;
        }
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
                exchange.getResponseHeaders().add("Set-Cookie", "username=" + encode(username) + "; Path=/; HttpOnly");
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
            String result = registerAccount(role, username, password, clean(form.getOrDefault("phone", "")));
            if (result.isEmpty()) {
                exchange.getResponseHeaders().add("Set-Cookie", "role=" + role + "; Path=/; HttpOnly");
                exchange.getResponseHeaders().add("Set-Cookie", "username=" + encode(username) + "; Path=/; HttpOnly");
                redirect(exchange, "/dashboard");
            } else {
                redirect(exchange, "/register?error=" + encode(result));
            }
            return;
        }
        if ("GET".equals(method) && "/logout".equals(path)) {
            exchange.getResponseHeaders().add("Set-Cookie", "role=; Max-Age=0; Path=/");
            exchange.getResponseHeaders().add("Set-Cookie", "username=; Max-Age=0; Path=/");
            redirect(exchange, "/");
            return;
        }

        String role = cookieValue(exchange, "role");
        if (role.isEmpty()) {
            redirect(exchange, "/");
            return;
        }
        if ("POST".equals(method) && "/donate".equals(path) && "donor".equals(role)) {
            addDonation(parseBody(exchange), cookieValue(exchange, "username"));
            redirect(exchange, "/dashboard?view=donor&saved=1");
            return;
        }
        if ("POST".equals(method) && "/claim".equals(path) && "consumer".equals(role)) {
            claimDonation(params.get("id"), parseBody(exchange), cookieValue(exchange, "username"));
            redirect(exchange, "/dashboard?view=consumer&claimed=1");
            return;
        }
        if ("POST".equals(method) && "/accept".equals(path) && "donor".equals(role)) {
            acceptClaim(params.get("id"), params.get("claim"), cookieValue(exchange, "username"));
            redirect(exchange, "/dashboard?view=donor&accepted=1");
            return;
        }
        if ("POST".equals(method) && "/reject".equals(path) && "donor".equals(role)) {
            rejectClaim(params.get("id"), params.get("claim"), cookieValue(exchange, "username"));
            redirect(exchange, "/dashboard?view=donor&rejected=1");
            return;
        }
        if ("POST".equals(method) && "/given".equals(path) && "donor".equals(role)) {
            confirmGiven(params.get("id"), params.get("claim"), cookieValue(exchange, "username"));
            redirect(exchange, "/dashboard?view=donor&given=1");
            return;
        }
        if ("POST".equals(method) && "/got".equals(path) && "consumer".equals(role)) {
            confirmGot(params.get("id"), params.get("claim"), cookieValue(exchange, "username"));
            redirect(exchange, "/dashboard?view=consumer&got=1");
            return;
        }
        if ("POST".equals(method) && "/message".equals(path)) {
            sendMessage(parseBody(exchange), cookieValue(exchange, "username"));
            redirect(exchange, "/dashboard?view=" + role + "&messaged=1");
            return;
        }
        if ("GET".equals(method) && "/admin-download".equals(path) && "admin".equals(role)) {
            sendActivityCsv(exchange);
            return;
        }
        if ("GET".equals(method) && "/dashboard".equals(path)) {
            String selectedView = params.getOrDefault("view", role);
            if (!selectedView.equals(role)) {
                selectedView = role;
            }
            sendHtml(exchange, dashboardPage(role, selectedView, params, cookieValue(exchange, "username")));
            return;
        }
        sendHtml(exchange, page("Not found", "<div class='empty'><h2>Page not found</h2><a class='button' href='/dashboard'>Back to dashboard</a></div>"), 404);
    }

    private static boolean validLogin(String role, String username, String password) {
        if ("admin".equals(role)) {
            return !ADMIN_USERNAME.isEmpty() && username.equals(ADMIN_USERNAME) && password.equals(ADMIN_PASSWORD);
        }
        synchronized (accounts) {
            UserAccount account = accounts.get(username);
            return account != null && account.role.equals(role) && account.password.equals(password);
        }
    }

    private static String registerAccount(String role, String username, String password, String phone) {
        if (!"donor".equals(role) && !"consumer".equals(role)) return "Choose Donor or Consumer as your role.";
        if (!username.matches("[a-z0-9._-]{3,24}")) return "Username must be 3-24 characters using letters, numbers, dots, _ or -.";
        if ("admin".equals(username)) return "That username is reserved.";
        if (password.length() < 6) return "Password must contain at least 6 characters.";
        if (!phone.isEmpty() && !phone.matches("[0-9+() .-]{7,25}")) return "Enter a valid phone number.";
        synchronized (accounts) {
            if (accounts.containsKey(username)) return "That username is already registered.";
            accounts.put(username, new UserAccount(role, password, phone));
            saveAccounts();
        }
        return "";
    }

    private static void loadAccounts() {
        if (!Files.exists(ACCOUNTS_FILE)) return;
        try {
            for (String line : Files.readAllLines(ACCOUNTS_FILE, StandardCharsets.UTF_8)) {
                String[] values = line.split("\\|", 4);
                if (values.length >= 3) accounts.put(values[0], new UserAccount(values[1], values[2], values.length == 4 ? values[3] : ""));
            }
        } catch (IOException ignored) {
            System.err.println("Could not load accounts.txt; starting with no registered users.");
        }
    }

    private static void saveAccounts() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, UserAccount> entry : accounts.entrySet()) {
            lines.add(entry.getKey() + "|" + entry.getValue().role + "|" + entry.getValue().password + "|" + entry.getValue().phone);
        }
        try {
            Files.write(ACCOUNTS_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            System.err.println("Could not save accounts.txt.");
        }
    }

    private static void addDonation(Map<String, String> form, String donorUsername) {
        String food = clean(form.getOrDefault("food", "Mixed food"));
        String quantity = clean(form.getOrDefault("quantity", "1 box"));
        String pickup = clean(form.getOrDefault("pickup", "Community center"));
        String pickupTime = clean(form.getOrDefault("pickupTime", ""));
        synchronized (donations) {
            String phone = clean(form.getOrDefault("phone", ""));
            if (!phone.matches("[0-9+() .-]{7,25}")) phone = accounts.containsKey(donorUsername) ? accounts.get(donorUsername).phone : "";
            donations.add(new Donation(donations.size() + 1, food, quantity, pickup, pickupTime, "Today", "Available", donorUsername, "", phone));
        }
    }

    private static void claimDonation(String id, Map<String, String> form, String consumerUsername) {
        if (id == null) return;
        int requestedQuantity;
        try {
            requestedQuantity = Integer.parseInt(form.getOrDefault("requestedQuantity", "0").trim());
        } catch (NumberFormatException ignored) {
            return;
        }
        if (requestedQuantity < 1) return;
        synchronized (donations) {
            for (Donation donation : donations) {
                if (String.valueOf(donation.id).equals(id) && donation.remainingQuantity >= requestedQuantity
                        && !hasClaim(donation, consumerUsername)) {
                    donation.remainingQuantity -= requestedQuantity;
                    donation.claims.add(new Claim(donation.claims.size() + 1, consumerUsername, requestedQuantity));
                    donation.status = donation.remainingQuantity > 0 ? "Available" : "Fully reserved";
                }
            }
        }
    }

    private static void acceptClaim(String id, String claimId, String donorUsername) {
        updateClaimDecision(id, claimId, donorUsername, "Request accepted");
    }

    private static void rejectClaim(String id, String claimId, String donorUsername) {
        updateClaimDecision(id, claimId, donorUsername, "Request rejected");
    }

    private static void updateClaimDecision(String id, String claimId, String donorUsername, String status) {
        if (id == null || claimId == null) return;
        synchronized (donations) {
            for (Donation donation : donations) {
                Claim claim = findClaim(donation, claimId);
                if (String.valueOf(donation.id).equals(id) && claim != null && donation.donorUsername.equals(donorUsername)
                        && "Claim requested".equals(claim.status)) {
                    claim.status = status;
                    if ("Request rejected".equals(status)) donation.remainingQuantity += claim.quantity;
                    donation.status = donation.remainingQuantity > 0 ? "Available" : "Fully reserved";
                }
            }
        }
    }

    private static void confirmGiven(String id, String claimId, String donorUsername) {
        if (id == null || claimId == null) return;
        synchronized (donations) {
            for (Donation donation : donations) {
                Claim claim = findClaim(donation, claimId);
                if (String.valueOf(donation.id).equals(id) && claim != null && donation.donorUsername.equals(donorUsername)
                        && "Request accepted".equals(claim.status)) {
                    claim.status = "Food given";
                }
            }
        }
    }

    private static void confirmGot(String id, String claimId, String consumerUsername) {
        if (id == null || claimId == null) return;
        synchronized (donations) {
            for (Donation donation : donations) {
                Claim claim = findClaim(donation, claimId);
                if (String.valueOf(donation.id).equals(id) && claim != null && claim.consumerUsername.equals(consumerUsername)
                        && "Food given".equals(claim.status)) {
                    claim.status = "Collected";
                }
            }
        }
    }

    private static boolean hasClaim(Donation donation, String consumerUsername) {
        for (Claim claim : donation.claims) {
            if (claim.consumerUsername.equals(consumerUsername) && !"Request rejected".equals(claim.status)) return true;
        }
        return false;
    }

    private static boolean hasAnyClaim(Donation donation, String consumerUsername) {
        for (Claim claim : donation.claims) {
            if (claim.consumerUsername.equals(consumerUsername)) return true;
        }
        return false;
    }

    private static Claim findClaim(Donation donation, String claimId) {
        try {
            int requestedId = Integer.parseInt(claimId);
            for (Claim claim : donation.claims) if (claim.id == requestedId) return claim;
        } catch (NumberFormatException ignored) {
            // Invalid claim ids are ignored.
        }
        return null;
    }

    private static void sendMessage(Map<String, String> form, String sender) {
        String recipient = clean(form.getOrDefault("recipient", ""));
        String body = clean(form.getOrDefault("message", ""));
        String donationId = clean(form.getOrDefault("donationId", ""));
        if (sender.isEmpty() || recipient.isEmpty() || body.isEmpty() || body.length() > 500) return;
        synchronized (accounts) {
            if (!accounts.containsKey(recipient)) return;
        }
        synchronized (messages) {
            messages.add(new Message(sender, recipient, body, donationId, LocalDate.now().toString()));
            saveMessages();
        }
    }

    private static void loadMessages() {
        if (!Files.exists(MESSAGES_FILE)) return;
        try {
            for (String line : Files.readAllLines(MESSAGES_FILE, StandardCharsets.UTF_8)) {
                String[] values = line.split("\\|", 5);
                if (values.length == 5) messages.add(new Message(values[0], values[1], values[2], values[3], values[4]));
            }
        } catch (IOException ignored) {
            System.err.println("Could not load messages.txt; starting with no messages.");
        }
    }

    private static void saveMessages() {
        List<String> lines = new ArrayList<>();
        for (Message message : messages) {
            lines.add(message.sender + "|" + message.recipient + "|" + message.body.replace("|", " ") + "|" + message.donationId + "|" + message.date);
        }
        try {
            Files.write(MESSAGES_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            System.err.println("Could not save messages.txt.");
        }
    }

    private static String dashboardPage(String role, String view, Map<String, String> params, String username) {
        int available = count("Available");
        int claimed = count("Claim requested") + count("Collected");
        int totalMeals = 0;
        String accountName = username.isEmpty() ? title(role) : username;
        synchronized (donations) {
            for (Donation donation : donations) {
                totalMeals += parseQuantity(donation.quantity);
            }
        }
        String content = "admin".equals(view) ? adminPanel(params, username) : "donor".equals(view) ? donorPanel(params, username) : consumerPanel(params, username);
        String roleTitle = title(role) + " panel";
        String nav = "<a class='nav-item " + active(role, "admin") + "' href='/dashboard?view=admin'><span>O</span> Overview</a>"
            + "<a class='nav-item " + active(role, "donor") + "' href='/dashboard?view=donor'><span>+</span> Donate food</a>"
            + "<a class='nav-item " + active(role, "consumer") + "' href='/dashboard?view=consumer'><span>H</span> Find food</a>";
        String stats = "admin".equals(view)
                ? "<section class='stats'>"
                    + stat("No. Consumers", String.valueOf(countAccounts("consumer")), "Registered consumers", "mint")
                    + stat("No. Donors", String.valueOf(countAccounts("donor")), "Registered donors", "peach")
                    + stat("Successfully shared food", String.valueOf(countCompletedShares()), "Both sides confirmed receipt", "blue")
                    + "</section>"
                : "<section class='stats'>"
                    + stat("Meals shared", String.valueOf(totalMeals), "Across all donations", "mint")
                    + stat("Available now", String.valueOf(available), "Ready for pickup", "peach")
                    + stat("Requests", String.valueOf(claimed), "In progress", "blue")
                    + "</section>";
        String body = "<div class='shell'><aside class='sidebar'><div class='brand'><img class='brand-logo' style='display:block;width:184px;height:auto;background:#fff;border-radius:8px' src='/logo.svg' alt='ShareTable logo'></div>"
                + "<div class='side-label'>Workspace</div>" + nav + "<div class='side-bottom'><div class='profile'><span class='avatar'>" + esc(accountName.substring(0, 1).toUpperCase()) + "</span><div><b>" + esc(accountName) + "</b><small>" + title(role) + " account · Active today</small></div></div><a class='logout' href='/logout'>Sign out</a></div></aside>"
                + "<main class='main'><header class='topbar'><div><p class='eyebrow'>Good food, shared well</p><h1>" + roleTitle + "</h1></div><div class='top-date'>" + LocalDate.now() + "<span class='live-dot'></span></div></header>" + stats + content + "</main></div>";
        return page("ShareTable | " + roleTitle, body);
    }

    private static String adminPanel(Map<String, String> params, String username) {
        StringBuilder rows = new StringBuilder();
        synchronized (donations) {
            for (Donation donation : donations) {
                rows.append("<tr><td><b>#").append(donation.id).append("</b></td><td><strong>").append(esc(donation.food)).append("</strong><small>").append(esc(donation.quantity)).append("</small></td><td>").append(esc(donation.pickup)).append("<small>Pickup at ").append(esc(formatPickupTime(donation.pickupTime))).append("</small></td><td><strong>").append(userDetails(donation.donorUsername, donation.donorPhone)).append("</strong></td><td><strong>").append(userDetails(donation.consumerUsername, "")).append("</strong></td><td>").append(badge(donation.status)).append("<small>Given: ").append(donation.donorGiven ? "Yes" : "No").append(" · Received: ").append(donation.consumerGot ? "Yes" : "No").append("</small></td></tr>");
            }
        }
        String notice = params.containsKey("messaged") ? "<div class='notice success'>Message sent.</div>" : params.containsKey("given") ? "<div class='notice success'>Food handoff marked as given.</div>" : "";
        return "<div class='admin-layout'>" + notice + "<div class='section-heading admin-heading'><div><p class='eyebrow'>Read-only overview</p><h2>Donor and consumer activity</h2><p class='section-copy'>A traceable record of every request, decision, and food handoff.</p></div><div class='admin-actions'><span class='count-pill'>" + donations.size() + " total records</span><a class='small-button download-button' href='/admin-download'>Download activity</a></div></div>"
            + "<section class='table-card admin-donations'><table><thead><tr><th>ID</th><th>Food</th><th>Pickup point and time</th><th>Donor details</th><th>Consumer details</th><th>Handoff record</th></tr></thead><tbody>" + rows + "</tbody></table></section>"
            + "<div class='admin-lower'><div class='admin-directory'>" + accountDirectory() + "</div>" + messagePanel("admin", username) + "</div></div>";
    }

    private static String userDetails(String username, String fallbackPhone) {
        if (username == null || username.isEmpty()) return "Waiting for consumer";
        UserAccount account = accounts.get(username);
        String phone = fallbackPhone;
        if (phone.isEmpty() && account != null) phone = account.phone;
        return esc(username) + (phone.isEmpty() ? "<small>Phone not provided</small>" : "<small>Phone: " + esc(phone) + "</small>");
    }

    private static void sendActivityCsv(HttpExchange exchange) throws IOException {
        StringBuilder csv = new StringBuilder("ID,Food,Quantity,Pickup,Pickup time,Donor,Donor phone,Consumer,Consumer phone,Status,Donor given,Consumer received\n");
            synchronized (donations) {
            for (Donation donation : donations) {
                csv.append(csvValue(String.valueOf(donation.id))).append(',')
                    .append(csvValue(donation.food)).append(',').append(csvValue(donation.quantity)).append(',')
                    .append(csvValue(donation.pickup)).append(',').append(csvValue(donation.pickupTime)).append(',').append(csvValue(donation.donorUsername)).append(',')
                    .append(csvValue(phoneFor(donation.donorUsername, donation.donorPhone))).append(',')
                    .append(csvValue(donation.consumerUsername)).append(',').append(csvValue(phoneFor(donation.consumerUsername, ""))).append(',')
                    .append(csvValue(donation.status)).append(',').append(donation.donorGiven ? "Yes" : "No").append(',')
                    .append(donation.consumerGot ? "Yes" : "No").append('\n');
            }
        }
        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=sharetable-activity.csv");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static String phoneFor(String username, String fallback) {
        if (username == null || username.isEmpty()) return "";
        UserAccount account = accounts.get(username);
        return fallback.isEmpty() && account != null ? account.phone : fallback;
    }

    private static String csvValue(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
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
                rows.append("<tr><td><strong>").append(esc(entry.getKey())).append("</strong></td><td>").append(title(account.role)).append("</td><td>").append(esc(account.phone.isEmpty() ? "Not provided" : account.phone)).append("</td></tr>");
            }
        }
        if (rows.length() == 0) {
            rows.append("<tr><td colspan='3'><div class='empty'>No Donor or Consumer accounts have registered yet.</div></td></tr>");
        }
        return "<div class='section-heading compact'><div><p class='eyebrow'>People</p><h2>Donor and Consumer directory</h2><p class='section-copy'>" + donors + " donors and " + consumers + " consumers registered.</p></div></div>"
                + "<section class='table-card'><table><thead><tr><th>Username</th><th>Account type</th><th>Phone</th></tr></thead><tbody>" + rows + "</tbody></table></section>";
    }

    private static String donorPanel(Map<String, String> params, String username) {
        String notice = params.containsKey("saved") ? "<div class='notice success'>Your donation is now visible to consumers.</div>" : params.containsKey("messaged") ? "<div class='notice success'>Message sent to the consumer.</div>" : params.containsKey("accepted") ? "<div class='notice success'>Consumer request accepted.</div>" : params.containsKey("rejected") ? "<div class='notice success'>Consumer request rejected.</div>" : params.containsKey("given") ? "<div class='notice success'>Food handoff marked as given.</div>" : "";
        return notice + "<div class='section-heading'><div><p class='eyebrow'>Make an impact</p><h2>Share surplus food</h2><p class='section-copy'>Tell local communities what you have available today.</p></div></div>"
                + "<section class='form-card'><form method='post' action='/donate'><div class='form-grid'><label>Food available<input name='food' required placeholder='e.g. Vegetable biryani'></label><label>Quantity<input name='quantity' required placeholder='e.g. 25 meals'></label><label>Contact phone<input name='phone' required pattern='[0-9+() .-]{7,25}' placeholder='e.g. +1 555 010 2020'></label><label>Food pickup time<input name='pickupTime' type='text' required placeholder='e.g. 10:30 AM, noon, or evening'></label><label class='wide'>Pickup location<input name='pickup' required placeholder='e.g. 14 Market Street'></label></div><button class='button' type='submit'>Publish donation <span>→</span></button></form></section>"
                + "<div class='section-heading compact'><div><p class='eyebrow'>Your activity</p><h2>Recent donations</h2></div></div>" + donationCards("donor", username) + messagePanel("donor", username);
    }

    private static String consumerPanel(Map<String, String> params, String username) {
        String notice = params.containsKey("claimed") ? "<div class='notice success'>Request sent. The donor will prepare your pickup.</div>" : params.containsKey("messaged") ? "<div class='notice success'>Message sent to the donor.</div>" : params.containsKey("got") ? "<div class='notice success'>Food receipt confirmed.</div>" : "";
        return notice + "<div class='section-heading'><div><p class='eyebrow'>Community shelf</p><h2>Food available near you</h2><p class='section-copy'>Reserve what you need and help prevent good food from going to waste.</p></div><span class='count-pill'>" + count("Available") + " available</span></div>" + donationCards("consumer", username) + messagePanel("consumer", username);
    }

    private static String donationCards(String panel, String currentUser) {
        StringBuilder cards = new StringBuilder("<section class='donation-grid'>");
        synchronized (donations) {
            for (Donation donation : donations) {
                boolean visible = "donor".equals(panel) ? donation.donorUsername.equals(currentUser)
                        : donation.remainingQuantity > 0 || hasAnyClaim(donation, currentUser);
                if (visible) {
                    String action = "consumer".equals(panel)
                            ? consumerClaimAction(donation, currentUser)
                            : donorClaimActions(donation);
                        String donorContact = "consumer".equals(panel) ? donorContact(donation) : "";
                    cards.append("<article class='donation-card'><div class='food-icon'>").append(foodIcon(donation.food)).append("</div><div class='card-main'><div class='card-top'><span class='category'>DONATION #").append(donation.id).append("</span>").append(badge(donation.status)).append("</div><h3>").append(esc(donation.food)).append("</h3><p class='quantity'>").append(esc(donation.quantity)).append(" <span>•</span> ").append(donation.remainingQuantity).append(" remaining <span>•</span> ").append(esc(donation.pickup)).append(" <span>•</span> Pickup at ").append(esc(formatPickupTime(donation.pickupTime))).append("</p>").append(donorContact).append("<div class='card-bottom'><small>Posted ").append(donation.date).append("</small>").append(action).append("</div></div></article>");
                }
            }
        }
        if (cards.toString().equals("<section class='donation-grid'>")) {
            cards.append("<div class='empty'><h3>No donations yet</h3><p>New food listings will appear here.</p></div>");
        }
        return cards.append("</section>").toString();
    }

    private static String consumerClaimAction(Donation donation, String currentUser) {
        StringBuilder content = new StringBuilder();
        if (donation.remainingQuantity > 0 && !hasClaim(donation, currentUser)) {
            content.append("<form class='claim-form' method='post' action='/claim?id=").append(donation.id).append("'><input name='requestedQuantity' type='number' min='1' max='").append(donation.remainingQuantity).append("' required placeholder='How many?'><button class='small-button' type='submit'>Request food</button></form>");
        }
        for (Claim claim : donation.claims) {
            if (claim.consumerUsername.equals(currentUser)) {
                content.append("<span class='claim-note'>Your request: ").append(claim.quantity).append(" · ").append(badge(claim.status)).append("</span>");
                if ("Food given".equals(claim.status)) {
                    content.append("<form method='post' action='/got?id=").append(donation.id).append("&claim=").append(claim.id).append("'><button class='small-button' type='submit'>Got the food</button></form>");
                }
            }
        }
        return content.toString();
    }

    private static String donorClaimActions(Donation donation) {
        if (donation.claims.isEmpty()) return "<span class='card-status'>" + badge(donation.status) + "</span>";
        StringBuilder content = new StringBuilder("<span class='claim-list'>");
        for (Claim claim : donation.claims) {
            content.append("<span class='claim-row'><b>").append(esc(claim.consumerUsername)).append(" · ").append(claim.quantity).append(" food</b>");
            if ("Claim requested".equals(claim.status)) {
                content.append("<form method='post' action='/accept?id=").append(donation.id).append("&claim=").append(claim.id).append("'><button class='small-button' type='submit'>Accept</button></form><form method='post' action='/reject?id=").append(donation.id).append("&claim=").append(claim.id).append("'><button class='small-button reject-button' type='submit'>Reject</button></form>");
            } else if ("Request accepted".equals(claim.status)) {
                content.append("<form method='post' action='/given?id=").append(donation.id).append("&claim=").append(claim.id).append("'><button class='small-button' type='submit'>Given food</button></form>");
            } else {
                content.append(badge(claim.status));
            }
            content.append("</span>");
        }
        return content.append("</span>").toString();
    }

    private static String donorContact(Donation donation) {
        UserAccount donor = accounts.get(donation.donorUsername);
        String phone = donation.donorPhone.isEmpty() ? donor == null ? "" : donor.phone : donation.donorPhone;
        return "<p class='contact-line'>Donor: <b>" + esc(donation.donorUsername) + "</b>" + (phone.isEmpty() ? "" : " · <a href='tel:" + esc(phone) + "'>" + esc(phone) + "</a>") + "</p>";
    }

    private static String formatPickupTime(String pickupTime) {
        if (pickupTime == null || pickupTime.isEmpty()) return "Time not provided";
        return pickupTime;
    }

    private static String messagePanel(String role, String currentUser) {
        StringBuilder content = new StringBuilder("<section class='message-card'><div class='section-heading compact'><div><p class='eyebrow'>Direct contact</p><h2>Messages</h2><p class='section-copy'>Keep pickup details clear and private inside ShareTable.</p></div></div><div class='message-list'>");
        boolean hasMessages = false;
        synchronized (messages) {
            String username = currentUser;
            for (Message message : messages) {
                if (message.sender.equals(username) || message.recipient.equals(username)) {
                    hasMessages = true;
                    content.append("<div class='message-row'><b>").append(esc(message.sender)).append(" → ").append(esc(message.recipient)).append("</b><span>").append(esc(message.body)).append(" · ").append(esc(message.date)).append("</span></div>");
                }
            }
        }
        if (!hasMessages) content.append("<div class='message-empty'>No messages yet. Start a conversation about pickup details.</div>");
        content.append("</div><form class='message-form' method='post' action='/message'><label>Send to<select name='recipient'>");
        synchronized (accounts) {
            for (Map.Entry<String, UserAccount> entry : accounts.entrySet()) {
                boolean allowedRecipient = "admin".equals(role)
                    ? "donor".equals(entry.getValue().role) || "consumer".equals(entry.getValue().role)
                    : (role.equals("donor") && "consumer".equals(entry.getValue().role))
                    || (role.equals("consumer") && "donor".equals(entry.getValue().role));
                if (!entry.getKey().equals(currentUser) && allowedRecipient) {
                    content.append("<option value='").append(esc(entry.getKey())).append("'>").append(esc(entry.getKey())).append("</option>");
                }
            }
        }
        content.append("</select></label><label>Message<textarea name='message' maxlength='500' required placeholder='Write a pickup or availability update'></textarea></label><button class='button' type='submit'>Send message <span>→</span></button></form></section>");
        return content.toString();
    }

    private static String loginPage(String error) {
        String notice = error == null ? "" : "<div class='notice error'>" + esc(error) + "</div>";
        String body = "<div class='login-layout'><div class='login-art'><div class='brand light'><img class='brand-logo' style='display:block;width:184px;height:auto;background:#fff;border-radius:8px' src='/logo.svg' alt='ShareTable logo'></div><div class='art-copy'><p class='eyebrow light-text'>A little extra can go a long way</p><h1>Good food belongs<br>on every table.</h1><p>Coordinate surplus food, local donors, and people who need a helping hand.</p></div><div class='art-footer'><span>♧</span> Building kinder communities, one meal at a time</div></div><div class='login-side'><div class='login-box'><p class='eyebrow'>Welcome to ShareTable</p><h2>Sign in to your workspace</h2><p class='login-subtitle'>Use your registered username and password.</p>" + notice + "<form method='post' action='/login'><label>Workspace role<select name='role'><option value='consumer'>Consumer</option><option value='donor'>Donor</option><option value='admin'>Admin</option></select></label><label>Username<input name='username' required placeholder='Enter username'></label><label>Password<input name='password' type='password' required placeholder='Enter password'></label><button class='button full' type='submit'>Continue <span>→</span></button></form><p class='account-link'>New here? <a href='/register'>Create your own account</a></p></div></div></div>";
        return page("ShareTable | Sign in", body);
    }

    private static String registerPage(String error) {
        String notice = error == null ? "" : "<div class='notice error'>" + esc(error) + "</div>";
        String body = "<div class='login-layout'><div class='login-art'><div class='brand light'><img class='brand-logo' style='display:block;width:184px;height:auto;background:#fff;border-radius:8px' src='/logo.svg' alt='ShareTable logo'></div><div class='art-copy'><p class='eyebrow light-text'>Join the table</p><h1>Turn extra food<br>into shared good.</h1><p>Create an account to donate surplus meals or request food from your local community.</p></div><div class='art-footer'>Your account is saved locally on this computer.</div></div><div class='login-side'><div class='login-box'><p class='eyebrow'>Create your account</p><h2>Start sharing today</h2><p class='login-subtitle'>Choose how you want to help the community.</p>" + notice + "<form method='post' action='/register'><label>Account type<select name='role'><option value='consumer'>Consumer - request food</option><option value='donor'>Donor - share food</option></select></label><label>Username<input name='username' required minlength='3' maxlength='24' placeholder='Choose a username'></label><label>Phone number<input name='phone' pattern='[0-9+() .-]{7,25}' placeholder='Optional contact number'></label><label>Password<input name='password' type='password' required minlength='6' placeholder='At least 6 characters'></label><button class='button full' type='submit'>Create account <span>→</span></button></form><p class='account-link'>Already registered? <a href='/'>Sign in here</a></p></div></div></div>";
        return page("ShareTable | Create account", body);
    }

    private static String page(String title, String body) {
        String liveScript = body.contains("class='shell'") ? liveDashboardScript() : "";
        return "<!doctype html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'><link rel='icon' type='image/svg+xml' href='/logo.svg'><title>" + title + "</title><style>" + styles() + messageStyles() + adminStyles() + "</style></head><body>" + body + liveScript + "</body></html>";
    }

    private static String liveDashboardScript() {
        return "<script>(function(){const interval=4000;let updating=false;async function syncDashboard(){if(updating||document.querySelector('input:focus,textarea:focus,select:focus'))return;updating=true;try{const response=await fetch(window.location.href,{cache:'no-store',headers:{'X-Live-Update':'1'}});if(!response.ok)return;const html=await response.text();const parsed=new DOMParser().parseFromString(html,'text/html');const current=document.querySelector('.main');const latest=parsed.querySelector('.main');if(current&&latest)current.replaceWith(latest);}catch(error){console.debug('Live dashboard update unavailable',error);}finally{updating=false;}}setInterval(syncDashboard,interval);})();</script>";
    }

    private static String adminStyles() {
        return ".admin-layout{display:grid;grid-template-columns:1fr;gap:20px}.admin-heading{margin:0}.admin-actions{display:flex;align-items:center;gap:10px}.download-button{display:inline-block;text-decoration:none}.admin-donations{min-height:190px}.admin-donations table{min-width:1050px}.admin-donations td small{display:block}.admin-lower{display:grid;grid-template-columns:1fr;gap:28px;align-items:start}.admin-directory .section-heading{margin:0 0 14px}.admin-directory .section-heading h2{font-size:20px}.admin-directory .table-card{height:100%}.admin-lower .message-card{margin:0;display:block}.admin-lower .message-card .section-heading{margin-bottom:14px}.admin-lower .message-form{margin-top:18px}.admin-lower .message-row{display:block}.admin-lower .message-row span{display:block;margin-top:5px;text-align:left}@media(max-width:600px){.admin-actions{align-items:flex-start;flex-direction:column}.download-button{width:100%;text-align:center}}";
    }

    private static String messageStyles() {
        return ".message-card{grid-column:1/-1;clear:both;margin-top:52px;padding:26px;background:#fff;border:1px solid #e5eeeb;border-radius:12px;display:grid;grid-template-columns:minmax(0,1.15fr) minmax(260px,.85fr);gap:18px 28px}.donation-grid + .message-card{margin-top:52px}.message-card .section-heading{grid-column:1/-1;margin:0}.message-list{min-height:110px;padding:4px 0}.message-row{display:flex;justify-content:space-between;gap:20px;padding:13px 15px;margin:7px 0;background:#f3f8f5;border-left:3px solid #ee805f;border-radius:7px;color:#193d38}.message-row b{font-size:12px;white-space:nowrap}.message-row span{color:#78908a;font-size:12px;text-align:right}.message-empty{padding:18px 0;color:#78908a;font-size:13px}.message-form{align-self:start;padding:18px;background:#f8fbf9;border:1px solid #e5eeeb;border-radius:9px;display:grid;gap:13px}.message-form label{display:grid;gap:7px;font-size:11px;font-weight:bold;color:#193d38}.message-form textarea{min-height:104px;resize:vertical}.message-form .button{width:100%}.handoff-actions{display:flex;gap:6px}.reject-button{background:#c76848}@media(max-width:760px){.message-card{display:block}.message-form{margin-top:18px}.message-row{display:block}.message-row span{display:block;margin-top:6px;text-align:left}.handoff-actions{flex-wrap:wrap}}";
    }

    private static String styles() {
        return "*{box-sizing:border-box}body{margin:0;background:#fbfdfb;color:#193d38;font:14px Arial,sans-serif}a{color:inherit;text-decoration:none}button,input,select{font:inherit}.shell{display:flex;min-height:100vh}.sidebar{width:240px;background:#174b43;color:white;padding:28px 20px;display:flex;flex-direction:column}.brand{display:flex;align-items:center;gap:10px;font-size:20px;font-weight:bold}.brand-mark{width:31px;height:31px;border-radius:9px;background:#ee805f;color:white;display:grid;place-items:center;font-size:20px}.side-label{color:#9cc1b7;text-transform:uppercase;font-size:10px;letter-spacing:1px;margin:65px 12px 12px}.nav-item{color:#b8d5ce;border-radius:8px;padding:12px;display:flex;gap:12px;margin:3px 0}.nav-item:hover,.nav-item.active{background:#28665b;color:white}.side-bottom{margin-top:auto}.profile{border-top:1px solid #397064;padding:18px 4px;display:flex;align-items:center;gap:9px}.avatar{background:#e9b58c;color:#174b43;width:32px;height:32px;display:grid;place-items:center;border-radius:50%;font-weight:bold}.profile b,.profile small{display:block}.profile b{font-size:12px}.profile small{color:#9cc1b7;font-size:11px;margin-top:3px}.logout{display:block;color:#b8d5ce;padding:8px;font-size:12px}.main{max-width:1240px;flex:1;padding:42px 7%;overflow:hidden}.topbar{display:flex;justify-content:space-between;margin-bottom:30px}.eyebrow{font-size:10px;text-transform:uppercase;letter-spacing:1.4px;color:#ee805f;font-weight:bold;margin:0 0 8px}.topbar h1{font-size:32px;margin:0}.top-date{color:#78908a;font-size:12px}.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:15px;margin-bottom:42px}.stat{padding:22px 24px;border-radius:12px}.stat.mint{background:#e2f3eb}.stat.peach{background:#fff0e6}.stat.blue{background:#e9f1f5}.stat-label,.stat-note{color:#78908a;font-size:12px}.stat-number{display:block;font-size:31px;font-weight:bold;margin:7px 0 2px}.section-heading{display:flex;justify-content:space-between;align-items:end;margin-bottom:18px}.section-heading h2{font-size:24px;margin:0}.section-copy{color:#78908a;margin:8px 0 0}.count-pill{background:#edf5f1;color:#197d70;font-size:11px;font-weight:bold;padding:8px 12px;border-radius:20px}.table-card,.form-card{background:white;border:1px solid #e5eeeb;border-radius:12px;overflow:auto}.table-card{padding:4px}table{border-collapse:collapse;width:100%;min-width:720px}th{text-align:left;color:#78908a;font-size:10px;text-transform:uppercase;letter-spacing:1px;padding:15px}td{padding:16px 15px;border-top:1px solid #e5eeeb;color:#55736b}td strong,td small{display:block}td strong{color:#193d38}td small{font-size:11px;margin-top:4px;color:#78908a}.badge{display:inline-block;border-radius:20px;padding:6px 9px;background:#eff7f2;color:#398367;font-size:10px;font-weight:bold;white-space:nowrap}.badge.claim-requested{background:#fff2e9;color:#cb704a}.badge.collected{background:#e9f1f5;color:#577c91}.inline-form{display:flex;gap:6px}.inline-form select{border:1px solid #e5eeeb;color:#78908a;font-size:11px;border-radius:5px;padding:5px}.small-button,.button{background:#197d70;border:0;color:white;border-radius:7px;padding:8px 11px;font-weight:bold;cursor:pointer}.button{padding:13px 18px}.button span{font-size:18px;margin-left:12px}.form-card{padding:28px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-bottom:24px}.form-grid .wide{grid-column:1/-1}label{display:block;color:#193d38;font-size:12px;font-weight:bold}input,select{display:block;width:100%;border:1px solid #dce9e4;border-radius:7px;background:#fbfdfb;padding:12px 13px;margin-top:8px;color:#193d38}.donation-grid{display:grid;grid-template-columns:repeat(2,minmax(280px,1fr));gap:16px}.donation-card{background:white;border:1px solid #e5eeeb;border-radius:12px;padding:18px;display:flex;gap:15px}.food-icon{background:#fff0e6;width:54px;height:54px;border-radius:11px;display:grid;place-items:center;font-size:24px}.card-main{flex:1}.card-top,.card-bottom{display:flex;justify-content:space-between;align-items:center;gap:8px}.category{color:#ee805f;font-size:9px;font-weight:bold;letter-spacing:1px}.donation-card h3{font-size:18px;margin:12px 0 5px}.quantity{color:#78908a;font-size:12px;margin:0}.card-bottom{border-top:1px solid #e5eeeb;margin-top:20px;padding-top:14px}.card-bottom small{color:#78908a;font-size:10px}.notice{border-radius:8px;padding:12px 15px;margin-bottom:22px;font-size:12px}.notice.success{background:#e6f5ed;color:#398367}.notice.error{background:#fff0ea;color:#c76848}.empty{padding:38px;text-align:center;border:1px dashed #d4e5df;color:#78908a;grid-column:1/-1}.login-layout{min-height:100vh;display:grid;grid-template-columns:46% 54%;background:#fbfdfb}.login-art{background:#174b43;padding:34px 8%;color:white;display:flex;flex-direction:column}.light{color:white}.art-copy{margin:auto 0}.art-copy h1{font-size:52px;line-height:1.06;margin:12px 0 22px}.art-copy p:not(.eyebrow){color:#c7e0d9;max-width:390px;line-height:1.7}.light-text{color:#f3af91}.art-footer{color:#a9cfc3;font-size:11px}.login-side{display:grid;place-items:center;padding:30px}.login-box{width:min(400px,100%)}.login-box h2{font-size:32px;margin:0 0 8px}.login-subtitle{color:#78908a;margin:0 0 28px;line-height:1.5}.login-box form label{margin-top:17px}.button.full{width:100%;margin-top:25px}.demo-box{background:#f0f7f3;border-radius:8px;padding:14px 16px;margin-top:28px;color:#78908a;font-size:11px}.demo-box b{color:#193d38;display:block;margin-bottom:7px}.demo-box span{margin-right:13px}@media(max-width:800px){.sidebar{width:200px}.main{padding:30px}.login-layout{grid-template-columns:1fr}.login-art{min-height:260px}.donation-grid{grid-template-columns:1fr}}@media(max-width:580px){.sidebar{width:66px;padding:20px 10px}.sidebar .brand span:last-child,.side-label,.nav-item:not(:first-of-type),.profile div,.logout{display:none}.nav-item{justify-content:center}.main{padding:25px 18px}.stats{grid-template-columns:1fr}.form-grid{grid-template-columns:1fr}.form-grid .wide{grid-column:auto}}";
    }

    @SuppressWarnings("unused")
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

    private static int countAccounts(String role) {
        int result = 0;
        synchronized (accounts) {
            for (UserAccount account : accounts.values()) {
                if (role.equals(account.role)) result++;
            }
        }
        return result;
    }

    private static int countCompletedShares() {
        int result = 0;
        synchronized (donations) {
            for (Donation donation : donations) {
                for (Claim claim : donation.claims) {
                    if ("Collected".equals(claim.status)) result += claim.quantity;
                }
            }
        }
        return result;
    }

    private static int parseQuantity(String quantity) {
        try {
            return Integer.parseInt(quantity.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return 1;
        }
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
        try (InputStream input = exchange.getRequestBody()) {
            return parseParams(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
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
        try (exchange) {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(303, -1);
        }
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

    private static void sendSvg(HttpExchange exchange) throws IOException {
        byte[] content = Files.readAllBytes(Paths.get("share-table-logo.svg"));
        exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static class Donation {
        private final int id;
        private final String food;
        private final String quantity;
        private final String pickup;
        private final String pickupTime;
        private final String date;
        private final String donorUsername;
        private final String donorPhone;
        private final List<Claim> claims = new ArrayList<>();
        private int remainingQuantity;
        private String status;
        private String consumerUsername;
        private boolean donorGiven;
        private boolean consumerGot;

        private Donation(int id, String food, String quantity, String pickup, String pickupTime, String date, String status, String donorUsername, String consumerUsername, String donorPhone) {
            this.id = id;
            this.food = food;
            this.quantity = quantity;
            this.pickup = pickup;
            this.pickupTime = pickupTime;
            this.date = date;
            this.status = status;
            this.donorUsername = donorUsername;
            this.consumerUsername = consumerUsername;
            this.donorPhone = donorPhone;
            this.remainingQuantity = parseQuantity(quantity);
        }
    }

    private static class Claim {
        private final int id;
        private final String consumerUsername;
        private final int quantity;
        private String status = "Claim requested";

        private Claim(int id, String consumerUsername, int quantity) {
            this.id = id;
            this.consumerUsername = consumerUsername;
            this.quantity = quantity;
        }
    }

    private static class UserAccount {
        private final String role;
        private final String password;
        private final String phone;

        private UserAccount(String role, String password, String phone) {
            this.role = role;
            this.password = password;
            this.phone = phone;
        }
    }

    private static class Message {
        private final String sender;
        private final String recipient;
        private final String body;
        private final String donationId;
        private final String date;

        private Message(String sender, String recipient, String body, String donationId, String date) {
            this.sender = sender;
            this.recipient = recipient;
            this.body = body;
            this.donationId = donationId;
            this.date = date;
        }
    }
}
