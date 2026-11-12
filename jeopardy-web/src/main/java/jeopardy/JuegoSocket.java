package jeopardy;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.websocket.*;
import javax.websocket.server.ServerEndpoint;

// Servidor del juego en vivo. Mensajes de texto separados por '|'.
@ServerEndpoint("/ws")
public class JuegoSocket {
    static final int RONDAS = 50, SEGUNDOS = 20;
    static final String[] CATS = {"SERVIDORES", "FRAMEWORKS", "JAVA WEB START", "JSP", "SERVLETS"};
    static final List<String[]> BANCO = cargar();
    static final Map<String, Juego> JUEGOS = new HashMap<>();
    static final Object LOCK = new Object();
    static final ScheduledExecutorService RELOJ = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r); t.setDaemon(true); return t; });

    static class Jugador { String nombre; int puntos, ganados, respuesta = -1; long ms; }
    static class Juego {
        String pin; Session host; int actual = -1; boolean abierta; long inicio;
        Map<Session, Jugador> jugadores = new LinkedHashMap<>();
        List<String[]> preguntas = new ArrayList<>();
        ScheduledFuture<?> timer;
    }

    static List<String[]> cargar() {
        List<String[]> l = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                JuegoSocket.class.getResourceAsStream("/preguntas.txt"), "UTF-8"))) {
            String s;
            while ((s = br.readLine()) != null) {
                String[] f = s.split("\\|", -1);
                if (f.length == 7) l.add(f);
            }
        } catch (Exception e) { throw new RuntimeException(e); }
        return l;
    }

    static void enviar(Session s, String m) {
        try { synchronized (s) { s.getBasicRemote().sendText(m); } } catch (Exception e) { }
    }
    static void todos(Juego j, String m) {
        enviar(j.host, m);
        for (Session s : j.jugadores.keySet()) enviar(s, m);
    }

    @OnMessage
    public void onMessage(String msg, Session s) {
        String[] p = msg.split("\\|", -1);
        synchronized (LOCK) {
            Juego j = (Juego) s.getUserProperties().get("juego");
            switch (p[0]) {
                case "CREAR": crear(s); break;
                case "UNIR": if (p.length >= 3 && j == null) unir(s, p[1], p[2]); break;
                case "INICIAR": case "SIGUIENTE":
                    if (j != null && j.host == s && !j.abierta) avanzar(j, p[0].equals("INICIAR"));
                    break;
                case "RESP": if (j != null && j.host != s && p.length > 1) responder(j, s, p[1]); break;
            }
        }
    }

    static void crear(Session s) {
        String pin;
        do { pin = String.valueOf(1000 + new Random().nextInt(9000)); } while (JUEGOS.containsKey(pin));
        Juego j = new Juego(); j.pin = pin; j.host = s;
        JUEGOS.put(pin, j);
        s.getUserProperties().put("juego", j);
        enviar(s, "PIN|" + pin);
    }

    static void unir(Session s, String pin, String nombre) {
        Juego j = JUEGOS.get(pin.trim());
        nombre = nombre.replaceAll("[|,:]", "").trim();
        if (nombre.length() > 15) nombre = nombre.substring(0, 15);
        if (j == null || j.actual >= 0) { enviar(s, "ERR|PIN invalido o partida ya iniciada"); return; }
        if (nombre.isEmpty()) { enviar(s, "ERR|Escribe un nombre"); return; }
        for (Jugador x : j.jugadores.values())
            if (x.nombre.equalsIgnoreCase(nombre)) { enviar(s, "ERR|Ese nombre ya esta en uso"); return; }
        Jugador g = new Jugador(); g.nombre = nombre;
        j.jugadores.put(s, g);
        s.getUserProperties().put("juego", j);
        enviar(s, "OK|" + nombre);
        lobby(j);
    }

    static void lobby(Juego j) {
        StringBuilder sb = new StringBuilder("LOBBY|");
        for (Jugador g : j.jugadores.values()) sb.append(g.nombre).append(',');
        enviar(j.host, sb.toString());
    }

    static void avanzar(Juego j, boolean inicio) {
        if (inicio != (j.actual == -1)) return;
        if (inicio) {
            if (j.jugadores.isEmpty()) { enviar(j.host, "AVISO|Aun no hay jugadores"); return; }
            List<String[]> l = new ArrayList<>(BANCO);
            Collections.shuffle(l);
            j.preguntas = l.subList(0, Math.min(RONDAS, l.size()));
        }
        if (j.actual + 1 >= j.preguntas.size()) { fin(j); return; }
        j.actual++;
        for (Jugador g : j.jugadores.values()) g.respuesta = -1;
        j.abierta = true;
        j.inicio = System.currentTimeMillis();
        String[] q = j.preguntas.get(j.actual);
        todos(j, "PREG|" + (j.actual + 1) + "|" + j.preguntas.size() + "|" + CATS[Integer.parseInt(q[0])]
                + "|" + q[1] + "|" + q[2] + "|" + q[3] + "|" + q[4] + "|" + q[5] + "|" + SEGUNDOS);
        final int idx = j.actual;
        j.timer = RELOJ.schedule(() -> { synchronized (LOCK) { cerrar(j, idx); } }, SEGUNDOS, TimeUnit.SECONDS);
    }

    static void responder(Juego j, Session s, String r) {
        Jugador g = j.jugadores.get(s);
        if (g == null || !j.abierta || g.respuesta != -1) return;
        int n;
        try { n = Integer.parseInt(r); } catch (Exception e) { return; }
        if (n < 0 || n > 3) return;
        g.respuesta = n;
        g.ms = System.currentTimeMillis() - j.inicio;
        comprobar(j);
    }

    // Cierra la pregunta si ya respondieron todos; si no, avisa al anfitrion el avance
    static void comprobar(Juego j) {
        int n = 0;
        for (Jugador g : j.jugadores.values()) if (g.respuesta != -1) n++;
        if (n == j.jugadores.size()) cerrar(j, j.actual);
        else enviar(j.host, "CONT|" + n + "|" + j.jugadores.size());
    }

    // Puntaje estilo Kahoot: 500 a 1000 puntos segun la rapidez; incorrecta = 0
    static void cerrar(Juego j, int idx) {
        if (!j.abierta || j.actual != idx) return;
        j.abierta = false;
        if (j.timer != null) j.timer.cancel(false);
        int correcta = Integer.parseInt(j.preguntas.get(idx)[6]) - 1;
        int[] c = new int[4];
        for (Jugador g : j.jugadores.values()) {
            g.ganados = 0;
            if (g.respuesta >= 0) c[g.respuesta]++;
            if (g.respuesta == correcta)
                g.ganados = (int) Math.max(500, 1000 - 500.0 * g.ms / (SEGUNDOS * 1000));
            g.puntos += g.ganados;
        }
        List<Jugador> rank = ranking(j);
        for (Map.Entry<Session, Jugador> e : j.jugadores.entrySet()) {
            Jugador g = e.getValue();
            enviar(e.getKey(), "RES|" + (g.respuesta == correcta ? 1 : 0) + "|" + g.ganados + "|"
                    + g.puntos + "|" + (rank.indexOf(g) + 1));
        }
        enviar(j.host, "RESH|" + correcta + "|" + c[0] + "," + c[1] + "," + c[2] + "," + c[3] + "|"
                + texto(rank, 5) + "|" + (idx + 1 >= j.preguntas.size() ? 1 : 0));
    }

    static List<Jugador> ranking(Juego j) {
        List<Jugador> r = new ArrayList<>(j.jugadores.values());
        r.sort((a, b) -> b.puntos - a.puntos);
        return r;
    }

    static String texto(List<Jugador> r, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.size() && i < max; i++)
            sb.append(r.get(i).nombre).append(':').append(r.get(i).puntos).append(',');
        return sb.toString();
    }

    static void fin(Juego j) {
        todos(j, "FIN|" + texto(ranking(j), 10));
        JUEGOS.remove(j.pin);
    }

    @OnClose
    public void onClose(Session s) {
        synchronized (LOCK) {
            Juego j = (Juego) s.getUserProperties().get("juego");
            if (j == null) return;
            if (j.host == s) {
                if (j.timer != null) j.timer.cancel(false);
                JUEGOS.remove(j.pin);
                for (Session x : j.jugadores.keySet()) enviar(x, "ERR|El anfitrion cerro la partida");
            } else {
                j.jugadores.remove(s);
                if (j.actual == -1) lobby(j);
                else if (j.abierta) comprobar(j);
            }
        }
    }

    @OnError
    public void onError(Session s, Throwable t) { }
}
