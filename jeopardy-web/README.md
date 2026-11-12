# Jeopardy Java Live (estilo Kahoot)
Servidor: Java + WebSocket (javax.websocket) sobre **Tomcat 9**. Cliente: HTML/JS.
1. `mvn clean package` genera `target/jeopardy.war`
2. Copia el WAR a `webapps/` de Tomcat 9 (o ejecutalo desde NetBeans) y abre `http://localhost:8080/jeopardy/`
3. El anfitrion (proyector) pulsa "Crear partida" y muestra el PIN.
4. Los jugadores abren `http://IP-DEL-SERVIDOR:8080/jeopardy/` desde su celular, ponen PIN y nombre.
Preguntas: `src/main/resources/preguntas.txt` (categoria|enunciado|op1|op2|op3|op4|correcta).
