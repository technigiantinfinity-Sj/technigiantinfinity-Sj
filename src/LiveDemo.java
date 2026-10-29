import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Real-time autocomplete window: suggestions update on every key press.
 *
 * Run from the project folder:   javac -d out src/*.java   then   java -cp out LiveDemo
 *
 * Type a sentence; the last word you are typing is completed.
 *   Up / Down      move through the suggestions
 *   Enter or Tab   accept the highlighted suggestion (its frequency goes up by 1)
 *   Double-click   accept a suggestion with the mouse
 *
 * Words you selected recently are shown first (marked "recent") when they match.
 * A word that is not in the dictionary is saved as a new word when you finish it
 * (Space, or Enter when nothing matches): it is inserted into the radix tree, put in
 * "Last selected", and from then on it is suggested.
 */
public class LiveDemo {

    /**
     * Size of the window and text. 0 = automatic (fits the screen).
     * If it still looks too small or too big, set a number here, e.g. 1.5 or 2.
     */
    static double SCALE = 0;

    /** At most this many recently selected words are shown above the normal suggestions. */
    static int MAX_RECENT = 3;

    /** The scale actually used (worked out in main). */
    private static double scale = 1;

    private final AutocompleteEngine engine = new AutocompleteEngine();

    private final JTextField input = new JTextField();
    /** One line in the suggestion list. */
    private record Row(String word, long frequency, boolean recent) {}

    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);
    private final JLabel status = new JLabel(" ");
    private final DefaultListModel<String> historyModel = new DefaultListModel<>();
    private final JComboBox<Integer> kBox = new JComboBox<>(new Integer[] {5, 10, 15});
    private final JLabel dictLabel = new JLabel(" ");

    private String currentPrefix = "";

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            scale = SCALE > 0 ? SCALE : autoScale();
            scaleDefaultFonts();                      // before any component is created
            new LiveDemo().show();
        });
    }

    /**
     * Windows and macOS already enlarge Java windows on high-resolution screens.
     * On Linux Java usually does not, so the window looks tiny: grow it to the screen height.
     */
    private static double autoScale() {
        boolean linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
        if (!linux) return 1;
        double already = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getDefaultTransform().getScaleX();
        if (already > 1.01) return 1;                 // Java is already scaling this screen
        int h = Toolkit.getDefaultToolkit().getScreenSize().height;
        return Math.max(1, Math.min(3, Math.round(h / 720.0 * 4) / 4.0));   // 1080p -> 1.5, 4K -> 3
    }

    /** Make every standard Swing font (buttons, labels, borders...) bigger by `scale`. */
    private static void scaleDefaultFonts() {
        if (scale == 1) return;
        UIDefaults d = UIManager.getLookAndFeelDefaults();
        for (Object key : java.util.Collections.list(d.keys())) {
            Object v = d.get(key);
            if (v instanceof Font) {
                Font f = (Font) v;
                d.put(key, new javax.swing.plaf.FontUIResource(f.deriveFont((float) (f.getSize2D() * scale))));
            }
        }
    }

    /** A size in pixels, scaled. */
    private static int px(int n) { return (int) Math.round(n * scale); }

    private void show() {
        JFrame frame = new JFrame("Radix Tree Autocomplete - live");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        Font big = new Font(Font.SANS_SERIF, Font.PLAIN, px(22));
        input.setFont(big);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, px(18)));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new SuggestionRenderer());
        list.setFocusable(false);                       // keep typing in the text field
        status.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, px(15)));

        // ---- top: dictionary buttons, k, text field
        JButton english = new JButton("50,000 English words");
        JButton sample = new JButton("25 sample words");
        english.addActionListener(e -> { loadEnglish(); refresh(); input.requestFocusInWindow(); });
        sample.addActionListener(e -> { loadSample(); refresh(); input.requestFocusInWindow(); });
        kBox.addActionListener(e -> { refresh(); input.requestFocusInWindow(); });

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(new JLabel("Dictionary:"));
        controls.add(english);
        controls.add(sample);
        controls.add(new JLabel("   top k:"));
        controls.add(kBox);
        controls.add(new JLabel("   "));
        controls.add(dictLabel);

        JPanel top = new JPanel(new BorderLayout(0, px(10)));
        top.add(controls, BorderLayout.NORTH);
        top.add(input, BorderLayout.CENTER);

        // ---- centre: suggestions, right: history
        JScrollPane listPane = new JScrollPane(list);
        listPane.setBorder(BorderFactory.createTitledBorder("Suggestions (updated on every key press)"));

        JList<String> historyList = new JList<>(historyModel);
        historyList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, px(15)));
        historyList.setFocusable(false);
        JScrollPane historyPane = new JScrollPane(historyList);
        historyPane.setBorder(BorderFactory.createTitledBorder("Last selected"));
        historyPane.setPreferredSize(new Dimension(px(190), 0));

        JLabel help = new JLabel("Up/Down: move    Enter or Tab: accept (frequency + 1)    Space after a new word: save it");
        help.setForeground(Color.GRAY);

        JPanel bottom = new JPanel(new GridLayout(2, 1, 0, 4));
        bottom.add(status);
        bottom.add(help);

        JPanel root = new JPanel(new BorderLayout(px(10), px(10)));
        root.setBorder(new EmptyBorder(px(14), px(14), px(14), px(14)));
        root.add(top, BorderLayout.NORTH);
        root.add(listPane, BorderLayout.CENTER);
        root.add(historyPane, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        // ---- behaviour
        input.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { refresh(); }
            public void removeUpdate(DocumentEvent e)  { refresh(); }
            public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        input.setFocusTraversalKeysEnabled(false);      // so Tab reaches our key listener
        input.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int n = model.size();
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN:
                        if (n > 0) list.setSelectedIndex(Math.min(n - 1, list.getSelectedIndex() + 1));
                        e.consume(); break;
                    case KeyEvent.VK_UP:
                        if (n > 0) list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1));
                        e.consume(); break;
                    case KeyEvent.VK_ENTER:
                    case KeyEvent.VK_TAB:
                        if (n > 0) accept(); else saveTypedWord(true);
                        e.consume(); break;
                    case KeyEvent.VK_SPACE:
                        saveTypedWord(false);       // the space itself is still typed
                        break;
                    default:
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) accept();
            }
        });

        loadEnglish();
        refresh();

        frame.setContentPane(root);
        frame.setSize(px(820), px(560));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        input.requestFocusInWindow();
    }

    // ------------------------------------------------------------ dictionaries

    private void loadEnglish() {
        engine.tree().clear();
        Path file = Path.of(AutocompleteEngine.WORD_FILE);
        if (!Files.exists(file)) {
            loadSample();
            status.setText("'" + file + "' not found - run from the project folder. Using the 25 sample words.");
            return;
        }
        try {
            long t = System.nanoTime();
            engine.load(file);
            long ms = (System.nanoTime() - t) / 1_000_000;
            dictLabel.setText(String.format("%,d words, %,d nodes (loaded in %d ms)", engine.tree().size(), engine.tree().nodeCount(), ms));
        } catch (Exception ex) {
            loadSample();
            status.setText("could not read " + file + ": " + ex.getMessage() + " - using the 25 sample words");
        }
    }

    private void loadSample() {
        engine.tree().clear();
        for (Object[] row : AutocompleteEngine.sampleWords()) engine.add((String) row[0], ((Number) row[1]).longValue());
        dictLabel.setText(String.format("%,d words, %,d nodes", engine.tree().size(), engine.tree().nodeCount()));
    }

    // ------------------------------------------------------------ live update

    /** The word being typed: everything after the last space. */
    private String lastWord() {
        String text = input.getText();
        int space = text.lastIndexOf(' ');
        return text.substring(space + 1);
    }

    /** Runs after every key press: completes the word being typed. */
    private void refresh() {
        model.clear();
        currentPrefix = "";
        String typed = lastWord();
        if (typed.isEmpty()) {
            status.setForeground(Color.DARK_GRAY);
            status.setText("Start typing...");
            return;
        }
        try {
            String prefix = Words.prefix(typed);
            int k = (Integer) kBox.getSelectedItem();
            long t = System.nanoTime();
            List<Suggestion> s = engine.complete(prefix, k);
            long micros = (System.nanoTime() - t) / 1_000;
            currentPrefix = prefix;

            // 1. recently selected words that start with the prefix, newest first
            java.util.Set<String> shown = new java.util.HashSet<>();
            for (String w : engine.history()) {
                if (shown.size() >= Math.min(MAX_RECENT, k)) break;
                long f = engine.tree().frequency(w);
                if (w.startsWith(prefix) && f >= 0 && shown.add(w)) model.addElement(new Row(w, f, true));
            }
            // 2. the normal top-k by frequency, without repeating a recent word
            for (Suggestion x : s) {
                if (model.size() >= k) break;
                if (shown.add(x.word())) model.addElement(new Row(x.word(), x.frequency(), false));
            }
            if (!model.isEmpty()) list.setSelectedIndex(0);
            status.setForeground(Color.DARK_GRAY);
            if (s.isEmpty()) {
                String part = engine.knownPart(prefix);
                status.setText("no words start with '" + prefix + "'"
                        + (part.isEmpty() ? "" : "  -  the longest part that matches is '" + part + "'")
                        + "  -  press Space or Enter to save it as a new word");
            } else {
                status.setText(String.format("%,d words start with '%s'   |   %d nodes expanded   |   %,d \u00b5s",
                        engine.tree().countPrefix(prefix), prefix, engine.tree().lastExpanded(), micros));
            }
        } catch (IllegalArgumentException ex) {
            status.setForeground(new Color(170, 40, 30));
            status.setText(ex.getMessage());
        }
    }

    /**
     * Accept the highlighted row: replace the word being typed and count the selection.
     * A "+ add" row first inserts the new word into the tree.
     */
    private void accept() {
        Row chosen = list.getSelectedValue();
        if (chosen == null) return;
        String text = input.getText();
        int space = text.lastIndexOf(' ');
        long f = engine.select(chosen.word());
        historyModel.clear();
        for (String w : new java.util.LinkedHashSet<>(engine.history())) historyModel.addElement(w);
        dictLabel.setText(String.format("%,d words, %,d nodes", engine.tree().size(), engine.tree().nodeCount()));
        input.setText(text.substring(0, space + 1) + chosen.word() + " ");   // triggers refresh()
        status.setForeground(new Color(30, 110, 50));
        status.setText("selected '" + chosen.word() + "' - its frequency is now " + String.format("%,d", f)
                + " (picked words rise in future suggestions)");
    }

    /**
     * The word just typed is not in the dictionary: insert it into the radix tree as a
     * new word (frequency 1) and add it to "Last selected". Known or invalid words are left alone.
     */
    private void saveTypedWord(boolean addSpace) {
        String word;
        try {
            word = Words.word(lastWord());
        } catch (IllegalArgumentException ex) {
            return;                                   // empty or invalid (e.g. c@t): nothing to save
        }
        if (engine.tree().frequency(word) >= 0) return;
        engine.add(word, 0);
        engine.select(word);                          // frequency 1, newest in the history
        historyModel.clear();
        for (String w : new java.util.LinkedHashSet<>(engine.history())) historyModel.addElement(w);
        dictLabel.setText(String.format("%,d words, %,d nodes", engine.tree().size(), engine.tree().nodeCount()));
        if (addSpace) input.setText(input.getText() + " ");
        SwingUtilities.invokeLater(() -> {            // after the space has been typed and the list redrawn
            status.setForeground(new Color(30, 110, 50));
            status.setText("new word '" + word + "' saved in the radix tree (frequency 1) - type its first letters to get it back");
        });
    }

    /** Shows "1.  the typed part in bold + the rest      frequency   recent". */
    private final class SuggestionRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean sel, boolean focus) {
            Row r = (Row) value;
            String w = r.word();
            int cut = Math.min(currentPrefix.length(), w.length());
            String text = String.format("<html><pre style='margin:0'>%2d.  <b>%s</b>%s%s%,d%s</pre></html>",
                    index + 1, esc(w.substring(0, cut)), esc(w.substring(cut)),
                    " ".repeat(Math.max(2, 24 - w.length())), r.frequency(),
                    r.recent() ? "   <font color='#2b59c3'>recent</font>" : "");
            return super.getListCellRendererComponent(l, text, index, sel, focus);
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

