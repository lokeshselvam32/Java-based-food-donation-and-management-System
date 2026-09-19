import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class LoginPage extends JFrame {
    private static final Color INK = new Color(31, 45, 43);
    private static final Color MUTED = new Color(104, 121, 116);
    private static final Color TEAL = new Color(29, 125, 111);
    private static final Color CORAL = new Color(238, 126, 92);
    private static final Color FIELD = new Color(247, 249, 247);

    private final JTextField emailField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JLabel messageLabel = new JLabel(" ");
    private final JButton loginButton = createLoginButton();

    public LoginPage() {
        setTitle("ShareTable | Sign in");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(920, 600));
        setSize(1080, 680);
        setLocationRelativeTo(null);

        JPanel background = new GradientPanel();
        background.setLayout(new GridBagLayout());
        background.add(createLoginCard());
        setContentPane(background);

        getRootPane().setDefaultButton(loginButton);
    }

    private JPanel createLoginCard() {
        JPanel card = new JPanel(new BorderLayout(0, 24));
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createEmptyBorder(48, 56, 44, 56));
        card.setPreferredSize(new Dimension(470, 540));

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BorderLayout(0, 10));

        JLabel brand = new JLabel("SHARETABLE");
        brand.setFont(new Font("SansSerif", Font.BOLD, 14));
        brand.setForeground(CORAL);
        heading.add(brand, BorderLayout.NORTH);

        JPanel titleBlock = new JPanel();
        titleBlock.setOpaque(false);
        titleBlock.setLayout(new BorderLayout(0, 7));
        JLabel title = new JLabel("Welcome back");
        title.setFont(new Font("SansSerif", Font.BOLD, 31));
        title.setForeground(INK);
        JLabel subtitle = new JLabel("Sign in to keep good food moving.");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 15));
        subtitle.setForeground(MUTED);
        titleBlock.add(title, BorderLayout.NORTH);
        titleBlock.add(subtitle, BorderLayout.SOUTH);
        heading.add(titleBlock, BorderLayout.CENTER);
        card.add(heading, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        constraints.gridx = 0;
        constraints.insets = new Insets(0, 0, 8, 0);

        constraints.gridy = 0;
        form.add(createFieldLabel("Email address"), constraints);
        constraints.gridy = 1;
        constraints.insets = new Insets(0, 0, 18, 0);
        configureField(emailField, "you@example.com");
        form.add(emailField, constraints);

        constraints.gridy = 2;
        constraints.insets = new Insets(0, 0, 8, 0);
        form.add(createFieldLabel("Password"), constraints);
        constraints.gridy = 3;
        constraints.insets = new Insets(0, 0, 9, 0);
        configureField(passwordField, "Enter your password");
        form.add(passwordField, constraints);

        constraints.gridy = 4;
        constraints.insets = new Insets(0, 0, 16, 0);
        JPanel options = new JPanel(new BorderLayout());
        options.setOpaque(false);
        JCheckBox showPassword = new JCheckBox("Show password");
        showPassword.setOpaque(false);
        showPassword.setForeground(MUTED);
        showPassword.setFont(new Font("SansSerif", Font.PLAIN, 12));
        showPassword.addActionListener(event -> passwordField.setEchoChar(
                showPassword.isSelected() ? (char) 0 : '\u2022'));
        JButton forgotButton = createTextButton("Forgot password?");
        forgotButton.addActionListener(event -> showMessage("Password reset is not connected yet."));
        options.add(showPassword, BorderLayout.WEST);
        options.add(forgotButton, BorderLayout.EAST);
        form.add(options, constraints);

        constraints.gridy = 5;
        constraints.insets = new Insets(0, 0, 10, 0);
        form.add(loginButton, constraints);
        constraints.gridy = 6;
        constraints.insets = new Insets(0, 0, 0, 0);
        messageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        messageLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        messageLabel.setForeground(CORAL);
        form.add(messageLabel, constraints);
        card.add(form, BorderLayout.CENTER);

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0));
        footer.setOpaque(false);
        JLabel prompt = new JLabel("New to ShareTable?");
        prompt.setFont(new Font("SansSerif", Font.PLAIN, 13));
        prompt.setForeground(MUTED);
        JButton signUpButton = createTextButton("Create an account");
        signUpButton.addActionListener(event -> showMessage("Account creation is not connected yet."));
        footer.add(prompt);
        footer.add(signUpButton);
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    private JButton createLoginButton() {
        JButton button = new JButton("Sign in");
        button.setFont(new Font("SansSerif", Font.BOLD, 15));
        button.setForeground(Color.WHITE);
        button.setBackground(TEAL);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setOpaque(true);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setPreferredSize(new Dimension(0, 48));
        button.addActionListener(this::handleLogin);
        return button;
    }

    private JButton createTextButton(String text) {
        JButton button = new JButton(text);
        button.setFont(new Font("SansSerif", Font.BOLD, 12));
        button.setForeground(TEAL);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private JLabel createFieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("SansSerif", Font.BOLD, 12));
        label.setForeground(INK);
        return label;
    }

    private void configureField(JTextField field, String tooltip) {
        field.setFont(new Font("SansSerif", Font.PLAIN, 14));
        field.setForeground(INK);
        field.setBackground(FIELD);
        field.setToolTipText(tooltip);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 229, 225)),
                BorderFactory.createEmptyBorder(10, 13, 10, 13)));
        field.setPreferredSize(new Dimension(0, 44));
    }

    private void handleLogin(ActionEvent event) {
        String email = emailField.getText().trim();
        char[] password = passwordField.getPassword();
        boolean valid = email.contains("@") && email.contains(".") && password.length >= 6;
        Arrays.fill(password, '\0');
        if (valid) {
            showMessage("Signed in successfully.");
            messageLabel.setForeground(TEAL);
        } else {
            showMessage("Enter a valid email and a password with 6+ characters.");
            messageLabel.setForeground(CORAL);
        }
    }

    private void showMessage(String message) {
        messageLabel.setText(message);
    }

    private static class GradientPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D pen = (Graphics2D) graphics.create();
            pen.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            pen.setPaint(new GradientPaint(0, 0, new Color(227, 242, 236), getWidth(), getHeight(), new Color(255, 239, 222)));
            pen.fillRect(0, 0, getWidth(), getHeight());
            pen.setColor(new Color(255, 255, 255, 105));
            pen.fillOval(-130, getHeight() - 190, 420, 420);
            pen.setColor(new Color(255, 255, 255, 80));
            pen.fillOval(getWidth() - 200, -170, 360, 360);
            pen.dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // The default Swing look and feel is still usable.
            }
            LoginPage page = new LoginPage();
            page.getRootPane().getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearMessage");
            page.getRootPane().getActionMap().put("clearMessage", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    page.showMessage(" ");
                }
            });
            page.setVisible(true);
        });
    }
}
