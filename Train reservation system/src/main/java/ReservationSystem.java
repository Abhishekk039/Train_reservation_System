import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;

/** Online Reservation System - Swing + JDBC + PostgreSQL. Single-file for easy running. */
public class ReservationSystem {

    public static void main(String[] args) {
        try {
            Db.init();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(null, "Database error: " + e.getMessage(),
                    "Fatal", JOptionPane.ERROR_MESSAGE);
            return;
        }
        SwingUtilities.invokeLater(() -> new LoginFrame().setVisible(true));
    }

    // ===================== DATABASE LAYER =====================
    record Booking(String pnr, String passenger, int trainNo, String trainName,
                   String classType, String date, String source, String destination) {
        String pretty() {
            return """
                    PNR            : %s
                    Passenger      : %s
                    Train          : %d - %s
                    Class          : %s
                    Date of Journey: %s
                    From           : %s
                    To             : %s
                    """.formatted(pnr, passenger, trainNo, trainName, classType, date, source, destination);
        }
    }

    static class Db {
        static final String URL =
                "jdbc:postgresql://[::1]:5432/reservation_db";

        static final String USER = "postgres";

        static final String PASS = "09022003";

        static Connection conn() throws SQLException {
            return DriverManager.getConnection(URL, USER, PASS);
        }

        static void init() throws SQLException {
            try (Connection c = conn(); Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS users(username TEXT PRIMARY KEY, password_hash TEXT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS trains(train_no INTEGER PRIMARY KEY, train_name TEXT NOT NULL)");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS reservations(
                          pnr TEXT PRIMARY KEY,
                          passenger_name TEXT NOT NULL,
                          train_no INTEGER NOT NULL,
                          train_name TEXT NOT NULL,
                          class_type TEXT NOT NULL,
                          journey_date TEXT NOT NULL,
                          source TEXT NOT NULL,
                          destination TEXT NOT NULL)""");

                // Seed default user: admin / 09022003
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO users(username, password_hash) VALUES(?, ?) ON CONFLICT (username) DO NOTHING")) {
                    ps.setString(1, "admin");
                    ps.setString(2, sha256("09022003"));
                    ps.executeUpdate();
                }
                // Seed sample trains
                Object[][] trains = {
                        {12001, "Bhopal Shatabdi"}, {12301, "Howrah Rajdhani"},
                        {12431, "Trivandrum Rajdhani"}, {12951, "Mumbai Rajdhani"},
                        {16650, "Parasuram Express"}, {16346, "Netravati Express"}
                };
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO trains(train_no, train_name) VALUES(?, ?) ON CONFLICT (train_no) DO NOTHING")) {
                    for (Object[] t : trains) {
                        ps.setInt(1, (Integer) t[0]);
                        ps.setString(2, (String) t[1]);
                        ps.executeUpdate();
                    }
                }
            }
        }

        static String sha256(String s) {
            try {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : h) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        static boolean login(String user, String pass) throws SQLException {
            String sql = "SELECT 1 FROM users WHERE username=? AND password_hash=?";
            try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, user);
                ps.setString(2, sha256(pass));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }

        static String trainName(int trainNo) throws SQLException {
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement("SELECT train_name FROM trains WHERE train_no=?")) {
                ps.setInt(1, trainNo);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }

        /** Inserts booking with a unique 10-digit PNR and returns the saved Booking. */
        static Booking book(String passenger, int trainNo, String trainName, String cls,
                            String date, String src, String dst) throws SQLException {
            try (Connection c = conn()) {
                String pnr;
                do {
                    pnr = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
                } while (find(c, pnr) != null);

                String sql = "INSERT INTO reservations VALUES(?,?,?,?,?,?,?,?)";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, pnr);
                    ps.setString(2, passenger);
                    ps.setInt(3, trainNo);
                    ps.setString(4, trainName);
                    ps.setString(5, cls);
                    ps.setString(6, date);
                    ps.setString(7, src);
                    ps.setString(8, dst);
                    ps.executeUpdate();
                }
                return new Booking(pnr, passenger, trainNo, trainName, cls, date, src, dst);
            }
        }

        static Booking find(String pnr) throws SQLException {
            try (Connection c = conn()) {
                return find(c, pnr);
            }
        }

        private static Booking find(Connection c, String pnr) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM reservations WHERE pnr=?")) {
                ps.setString(1, pnr);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new Booking(rs.getString("pnr"), rs.getString("passenger_name"),
                            rs.getInt("train_no"), rs.getString("train_name"),
                            rs.getString("class_type"), rs.getString("journey_date"),
                            rs.getString("source"), rs.getString("destination"));
                }
            }
        }

        static boolean cancel(String pnr) throws SQLException {
            try (Connection c = conn();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM reservations WHERE pnr=?")) {
                ps.setString(1, pnr);
                return ps.executeUpdate() > 0;
            }
        }
    }

    // ===================== LOGIN FORM =====================
    static class LoginFrame extends JFrame {
        private final JTextField userField = new JTextField(16);
        private final JPasswordField passField = new JPasswordField(16);

        LoginFrame() {
            super("Online Reservation System - Login");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            JPanel p = new JPanel(new GridBagLayout());
            p.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
            addRow(p, 0, "Username:", userField);
            addRow(p, 1, "Password:", passField);

            JButton login = new JButton("Login");
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = 2; g.gridwidth = 2; g.insets = new Insets(15, 0, 0, 0);
            p.add(login, g);

            login.addActionListener(e -> doLogin());
            getRootPane().setDefaultButton(login);
            setContentPane(p);
            pack();
            setLocationRelativeTo(null);
        }

        private void doLogin() {
            String u = userField.getText().trim();
            String pw = new String(passField.getPassword());
            if (u.isEmpty() || pw.isEmpty()) {
                error(this, "Username and password are required.");
                return;
            }
            try {
                if (Db.login(u, pw)) {
                    new MainFrame(u).setVisible(true);
                    dispose();
                } else {
                    error(this, "Access denied: invalid username or password.");
                    passField.setText("");
                }
            } catch (SQLException ex) {
                error(this, "Database error: " + ex.getMessage());
            }
        }
    }

    // ===================== MAIN WINDOW (tabs) =====================
    static class MainFrame extends JFrame {
        MainFrame(String user) {
            super("Online Reservation System - Logged in as " + user);
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Book Ticket", new ReservationPanel());
            tabs.addTab("Cancel Ticket", new CancellationPanel());
            setContentPane(tabs);
            setSize(520, 450);
            setLocationRelativeTo(null);
        }
    }

    // ===================== RESERVATION FORM =====================
    static class ReservationPanel extends JPanel {
        private final JTextField nameField = new JTextField(20);
        private final JTextField trainNoField = new JTextField(20);
        private final JTextField trainNameField = new JTextField(20);
        private final JComboBox<String> classBox =
                new JComboBox<>(new String[]{"SL", "3A", "2A", "1A", "CC", "2S"});
        private final JTextField dateField = new JTextField(20);
        private final JTextField srcField = new JTextField(20);
        private final JTextField dstField = new JTextField(20);

        ReservationPanel() {
            setLayout(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
            trainNameField.setEditable(false);
            dateField.setToolTipText("Format: yyyy-MM-dd");

            addRow(this, 0, "Passenger Name:", nameField);
            addRow(this, 1, "Train Number:", trainNoField);
            addRow(this, 2, "Train Name:", trainNameField);
            addRow(this, 3, "Class Type:", classBox);
            addRow(this, 4, "Date (yyyy-MM-dd):", dateField);
            addRow(this, 5, "Source Station:", srcField);
            addRow(this, 6, "Destination Station:", dstField);

            JButton book = new JButton("Book / Insert");
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = 7; g.gridwidth = 2; g.insets = new Insets(15, 0, 0, 0);
            add(book, g);

            // Auto-populate train name as the user types the train number
            trainNoField.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { lookupTrain(); }
                public void removeUpdate(DocumentEvent e) { lookupTrain(); }
                public void changedUpdate(DocumentEvent e) { lookupTrain(); }
            });
            book.addActionListener(e -> doBook());
        }

        private void lookupTrain() {
            String t = trainNoField.getText().trim();
            if (!t.matches("\\d+")) { trainNameField.setText(""); return; }
            try {
                String name = Db.trainName(Integer.parseInt(t));
                trainNameField.setText(name == null ? "Train not found" : name);
            } catch (NumberFormatException | SQLException ex) {
                trainNameField.setText("");
            }
        }

        private void doBook() {
            String name = nameField.getText().trim();
            String trainNoText = trainNoField.getText().trim();
            String date = dateField.getText().trim();
            String src = srcField.getText().trim();
            String dst = dstField.getText().trim();

            // ---- validation ----
            if (name.isEmpty() || trainNoText.isEmpty() || date.isEmpty() || src.isEmpty() || dst.isEmpty()) {
                error(this, "All fields are required.");
                return;
            }
            if (!trainNoText.matches("\\d{1,9}")) {
                error(this, "Train number must be numeric.");
                return;
            }
            LocalDate journey;
            try {
                journey = LocalDate.parse(date); // strict ISO yyyy-MM-dd
            } catch (DateTimeParseException ex) {
                error(this, "Invalid date. Use format yyyy-MM-dd (e.g., 2026-12-25).");
                return;
            }
            if (journey.isBefore(LocalDate.now())) {
                error(this, "Date of journey cannot be in the past.");
                return;
            }
            if (src.equalsIgnoreCase(dst)) {
                error(this, "Source and destination cannot be the same.");
                return;
            }

            try {
                int trainNo = Integer.parseInt(trainNoText);
                String trainName = Db.trainName(trainNo);
                if (trainName == null) {
                    error(this, "Train number " + trainNo + " does not exist.");
                    return;
                }
                Booking b = Db.book(name, trainNo, trainName, (String) classBox.getSelectedItem(),
                        journey.toString(), src, dst);
                JOptionPane.showMessageDialog(this, "Booking Confirmed!\n\n" + b.pretty(),
                        "Reservation Successful", JOptionPane.INFORMATION_MESSAGE);
                clear();
            } catch (SQLException ex) {
                error(this, "Database error: " + ex.getMessage());
            }
        }

        private void clear() {
            nameField.setText(""); trainNoField.setText(""); trainNameField.setText("");
            dateField.setText(""); srcField.setText(""); dstField.setText("");
            classBox.setSelectedIndex(0);
        }
    }

    // ===================== CANCELLATION FORM =====================
    static class CancellationPanel extends JPanel {
        private final JTextField pnrField = new JTextField(14);
        private final JTextArea details = new JTextArea(10, 36);
        private final JButton cancelBtn = new JButton("Confirm Cancellation");
        private Booking current;

        CancellationPanel() {
            setLayout(new BorderLayout(10, 10));
            setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JButton fetch = new JButton("Fetch");
            top.add(new JLabel("PNR Number:"));
            top.add(pnrField);
            top.add(fetch);

            details.setEditable(false);
            details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            cancelBtn.setEnabled(false);

            add(top, BorderLayout.NORTH);
            add(new JScrollPane(details), BorderLayout.CENTER);
            add(cancelBtn, BorderLayout.SOUTH);

            fetch.addActionListener(e -> doFetch());
            cancelBtn.addActionListener(e -> doCancel());
        }

        private void doFetch() {
            String pnr = pnrField.getText().trim();
            current = null;
            cancelBtn.setEnabled(false);
            details.setText("");
            if (pnr.isEmpty()) { error(this, "Please enter a PNR number."); return; }
            if (!pnr.matches("\\d{10}")) { error(this, "PNR must be a 10-digit number."); return; }
            try {
                Booking b = Db.find(pnr);
                if (b == null) {
                    error(this, "No booking found for PNR " + pnr);
                    return;
                }
                current = b;
                details.setText(b.pretty());
                cancelBtn.setEnabled(true);
            } catch (SQLException ex) {
                error(this, "Database error: " + ex.getMessage());
            }
        }

        private void doCancel() {
            if (current == null) return;
            int choice = JOptionPane.showConfirmDialog(this,
                    "Are you sure you want to cancel PNR " + current.pnr() + "?",
                    "Confirm Cancellation", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
            try {
                if (Db.cancel(current.pnr())) {
                    JOptionPane.showMessageDialog(this, "Booking " + current.pnr() + " cancelled successfully.",
                            "Cancelled", JOptionPane.INFORMATION_MESSAGE);
                    current = null;
                    details.setText("");
                    pnrField.setText("");
                    cancelBtn.setEnabled(false);
                } else {
                    error(this, "Booking could not be cancelled (already removed?).");
                }
            } catch (SQLException ex) {
                error(this, "Database error: " + ex.getMessage());
            }
        }
    }

    // ===================== HELPERS =====================
    static void addRow(JPanel p, int row, String label, JComponent field) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridy = row; g.insets = new Insets(5, 5, 5, 5); g.anchor = GridBagConstraints.WEST;
        g.gridx = 0;
        p.add(new JLabel(label), g);
        g.gridx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        p.add(field, g);
    }

    static void error(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }
}
