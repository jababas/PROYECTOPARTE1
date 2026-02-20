import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

public class ProcessManagerSimple extends JFrame {

    static DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("hh:mm:ss a");

    // ───────── MODELOS ─────────
    static class Proceso {
        String nombre;
        int tamaño;
        String llegada, entrada = "-", salida = "-", espera = "-";
        LocalTime inicioEspera;
        String estado = "En memoria";

        Proceso(String n, int t) {
            nombre = n;
            tamaño = t;
            llegada = LocalTime.now().format(FMT);
        }
    }

    static class BloqueMemoria {
        int tamaño;
        Proceso proceso; // null = libre

        BloqueMemoria(int t, Proceso p) {
            tamaño = t;
            proceso = p;
        }

        boolean libre() { return proceso == null; }
    }

    // ───────── ESTADO ─────────
    int MEMORIA_TOTAL;
    List<BloqueMemoria> memoria = new ArrayList<>();
    List<Proceso> colaEspera = new ArrayList<>();
    List<Proceso> historial = new ArrayList<>();

    // ───────── UI ─────────
    JTable tabla;
    ModeloTabla modelo;
    MemoryPanel memoryPanel;
    JLabel lblMemoriaGlobal;

    public ProcessManagerSimple() {

        MEMORIA_TOTAL = Integer.parseInt(
                JOptionPane.showInputDialog(
                        "¿Cuánta memoria total deseas?")
        );

        memoria.add(new BloqueMemoria(MEMORIA_TOTAL, null));

        setTitle("Gestión de Memoria (con unión de huecos)");
        setSize(1200, 580);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        lblMemoriaGlobal = new JLabel();
        actualizarLabelMemoria();

        JButton btnAdd = new JButton("Llegada");
        JButton btnOut = new JButton("Salida");

        btnAdd.addActionListener(e -> agregarProceso());
        btnOut.addActionListener(e -> sacarProceso());

        JPanel top = new JPanel(new BorderLayout());
        top.add(lblMemoriaGlobal, BorderLayout.WEST);

        JPanel botones = new JPanel();
        botones.add(btnAdd);
        botones.add(btnOut);
        top.add(botones, BorderLayout.EAST);

        modelo = new ModeloTabla();
        tabla = new JTable(modelo);
        aplicarColoresTabla();

        memoryPanel = new MemoryPanel();

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                memoryPanel,
                new JScrollPane(tabla)
        );
        split.setDividerLocation(300);

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        setVisible(true);
    }

    // ───────── LÓGICA ─────────
    void agregarProceso() {
        String nombre = JOptionPane.showInputDialog(this, "Nombre:");
        if (nombre == null || nombre.isBlank()) return;

        int tamaño;
        try {
            tamaño = Integer.parseInt(
                    JOptionPane.showInputDialog(this, "Tamaño:")
            );
        } catch (Exception e) { return; }

        Proceso p = new Proceso(nombre, tamaño);
        historial.add(p);

        if (!intentarAsignar(p)) {
            p.estado = "En espera";
            p.inicioEspera = LocalTime.now();
            colaEspera.add(p);
        }

        actualizarTodo();
    }

    boolean intentarAsignar(Proceso p) {
        for (int i = 0; i < memoria.size(); i++) {
            BloqueMemoria b = memoria.get(i);

            if (b.libre() && b.tamaño >= p.tamaño) {
                b.proceso = p;

                if (b.tamaño > p.tamaño) {
                    memoria.add(i + 1,
                            new BloqueMemoria(
                                    b.tamaño - p.tamaño,
                                    null));
                }

                b.tamaño = p.tamaño;
                p.entrada = LocalTime.now().format(FMT);
                p.estado = "En memoria";

                if (p.inicioEspera != null) {
                    long seg = Duration.between(
                            p.inicioEspera,
                            LocalTime.now()).getSeconds();
                    p.espera = seg + " s";
                }
                return true;
            }
        }
        return false;
    }

    void sacarProceso() {
        List<BloqueMemoria> ocupados = memoria.stream()
                .filter(b -> !b.libre())
                .toList();

        if (ocupados.isEmpty()) return;

        String[] nombres = ocupados.stream()
                .map(b -> b.proceso.nombre)
                .toArray(String[]::new);

        String sel = (String) JOptionPane.showInputDialog(
                this, "Proceso a sacar:",
                "Salida", JOptionPane.PLAIN_MESSAGE,
                null, nombres, nombres[0]);

        if (sel == null) return;

        for (BloqueMemoria b : memoria) {
            if (!b.libre() && b.proceso.nombre.equals(sel)) {
                b.proceso.salida = LocalTime.now().format(FMT);
                b.proceso.estado = "Terminado";
                b.proceso = null;

                unirBloquesLibres();      // 👈 AQUÍ ESTÁ LA CLAVE
                intentarEntrarDesdeCola();
                actualizarTodo();
                return;
            }
        }
    }

    // 🔥 UNE BLOQUES LIBRES CONTIGUOS
    void unirBloquesLibres() {
        for (int i = 0; i < memoria.size() - 1; i++) {
            BloqueMemoria a = memoria.get(i);
            BloqueMemoria b = memoria.get(i + 1);

            if (a.libre() && b.libre()) {
                a.tamaño += b.tamaño;
                memoria.remove(i + 1);
                i--; // volver a evaluar
            }
        }
    }

    void intentarEntrarDesdeCola() {
        Iterator<Proceso> it = colaEspera.iterator();
        while (it.hasNext()) {
            Proceso p = it.next();
            if (intentarAsignar(p)) it.remove();
        }
    }

    void actualizarLabelMemoria() {
        int usada = memoria.stream()
                .filter(b -> !b.libre())
                .mapToInt(b -> b.tamaño).sum();

        lblMemoriaGlobal.setText(
                "Memoria usada: " + usada +
                        " / " + MEMORIA_TOTAL +
                        " | Libre: " + (MEMORIA_TOTAL - usada) +
                        " | En espera: " + colaEspera.size()
        );
    }

    void actualizarTodo() {
        actualizarLabelMemoria();
        memoryPanel.repaint();
        modelo.fireTableDataChanged();
    }

    // ───────── PANEL MEMORIA ─────────
    class MemoryPanel extends JPanel {
        MemoryPanel() {
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(300, 0));
        }

        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;

            int h = getHeight() - 60;
            int y = 30;

            for (BloqueMemoria b : memoria) {
                int bh = (int)
                        ((double) b.tamaño / MEMORIA_TOTAL * h);

                if (b.libre()) {
                    g.setColor(Color.LIGHT_GRAY);
                    g.fillRect(40, y, 180, bh);
                    g.setColor(Color.BLACK);
                    g.drawRect(40, y, 180, bh);
                    g.drawString("Libre (" + b.tamaño + ")", 45, y + bh / 2);
                } else {
                    g.setColor(Color.GREEN);
                    g.fillRect(40, y, 180, bh);
                    g.setColor(Color.BLACK);
                    g.drawRect(40, y, 180, bh);
                    g.drawString(
                            b.proceso.nombre + " (" + b.tamaño + ")",
                            45, y + bh / 2);
                }
                y += bh;
            }
        }
    }

    // ───────── TABLA ─────────
    class ModeloTabla extends AbstractTableModel {

        String[] cols = {
                "Nombre", "Tamaño", "Llegada",
                "Entrada", "Espera", "Salida", "Estado"
        };

        public int getRowCount() { return historial.size(); }
        public int getColumnCount() { return cols.length; }
        public String getColumnName(int c) { return cols[c]; }

        public Object getValueAt(int r, int c) {
            Proceso p = historial.get(r);
            return switch (c) {
                case 0 -> p.nombre;
                case 1 -> p.tamaño;
                case 2 -> p.llegada;
                case 3 -> p.entrada;
                case 4 -> p.espera;
                case 5 -> p.salida;
                case 6 -> p.estado;
                default -> "";
            };
        }
    }

    // ───────── COLORES TABLA ─────────
    void aplicarColoresTabla() {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel,
                    boolean foc, int row, int col) {

                super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                String estado = (String) t.getValueAt(row, 6);

                if ("En memoria".equals(estado))
                    setBackground(new Color(180, 255, 180));
                else if ("En espera".equals(estado))
                    setBackground(new Color(255, 230, 180));
                else
                    setBackground(new Color(220, 220, 220));

                if (sel) setBackground(Color.CYAN);
                return this;
            }
        };
        for (int i = 0; i < tabla.getColumnCount(); i++)
            tabla.getColumnModel().getColumn(i).setCellRenderer(r);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ProcessManagerSimple::new);
    }
}