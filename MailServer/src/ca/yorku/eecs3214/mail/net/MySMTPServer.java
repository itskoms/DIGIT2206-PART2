package ca.yorku.eecs3214.mail.net;

import ca.yorku.eecs3214.mail.mailbox.MailWriter;
import ca.yorku.eecs3214.mail.mailbox.Mailbox;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MySMTPServer extends Thread {

    private final Socket socket;
    private final BufferedReader socketIn;
    private final PrintWriter socketOut;

    private String sender = null;
    private final List<String> recipients = new ArrayList<>();
    private boolean waitingForData = false;
    private final StringBuilder messageData = new StringBuilder();
    private boolean isQuit = false;
    private boolean isHeloReceived = false;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("<([^<>@\\s]+@[^<>@\\s]+)>");

    public MySMTPServer(Socket socket) throws IOException {
        this.socket = socket;
        this.socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.socketOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
    }

    @Override
    public void run() {
        try (this.socket) {
            socketOut.println("220 " + getHostName() + " SMTP server ready");

            String inputLine;
            while ((inputLine = socketIn.readLine()) != null && !isQuit) {
                if (inputLine.trim().isEmpty()) continue;

                if (waitingForData) {
                    if (inputLine.equals(".")) {
                        writeEmailToMailboxes();
                        resetTransaction();
                        socketOut.println("250 OK");
                        waitingForData = false;
                    } else {
                        messageData.append(inputLine).append("\r\n");
                    }
                    continue;
                }

                String response = handleCommand(inputLine);
                if (response != null) {
                    socketOut.println(response);
                }
            }

        } catch (IOException e) {
            System.err.println("Error in client's connection handling.");
            e.printStackTrace();
        }
    }

    private String handleCommand(String inputLine) {
        String[] parts = inputLine.trim().split("\\s+", 2);
        String command = parts[0].toUpperCase();
        String argument = parts.length > 1 ? parts[1].trim() : null;

        switch (command) {
            case "HELO":
            case "EHLO":
                isHeloReceived = true;
                return "250 " + getHostName();
            case "MAIL":
                if (!isHeloReceived) return "503 Bad sequence of commands";
                return handleMailFrom(inputLine);
            case "RCPT":
                if (sender == null) return "503 Need MAIL before RCPT";
                return handleRcptTo(inputLine);
            case "DATA":
                if (sender == null || recipients.isEmpty()) return "503 Bad sequence of commands";
                waitingForData = true;
                messageData.setLength(0);
                return "354 Start mail input; end with <CRLF>.<CRLF>";
            case "RSET":
                resetTransaction();
                return "250 OK";
            case "NOOP":
                return "250 OK";
            case "QUIT":
                isQuit = true;
                return "221 " + getHostName() + " Service closing transmission channel";
            case "VRFY":
                return handleVrfy(argument);
            default:
                return "500 Syntax error, command unrecognized";
        }
    }

    private String handleMailFrom(String input) {
        Matcher matcher = EMAIL_PATTERN.matcher(input);
        if (!matcher.find()) return "501 Syntax error in parameters or arguments";

        sender = matcher.group(1);
        recipients.clear();
        return "250 OK";
    }

    private String handleRcptTo(String input) {
        Matcher matcher = EMAIL_PATTERN.matcher(input);
        if (!matcher.find()) return "501 Syntax error in parameters or arguments";

        String recipient = matcher.group(1);
        if (!Mailbox.isValidUser(recipient)) return "550 No such user here";

        recipients.add(recipient);
        return "250 OK";
    }

    private String handleVrfy(String argument) {
        if (argument == null || argument.isEmpty()) return "501 Syntax error in parameters or arguments";
        if (Mailbox.isValidUser(argument)) return "250 " + argument;
        return "550 No such user here";
    }

    private void writeEmailToMailboxes() {
        try {
            List<Mailbox> boxes = new ArrayList<>();
            for (String recipient : recipients) {
                boxes.add(new Mailbox(recipient));
            }

            try (MailWriter writer = new MailWriter(boxes)) {
                writer.write(messageData.toString());
            }

        } catch (Exception e) {
            System.err.println("Error writing email to mailbox: " + e.getMessage());
        }
    }

    private void resetTransaction() {
        sender = null;
        recipients.clear();
        messageData.setLength(0);
        waitingForData = false;
    }

    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(Runtime.getRuntime().exec("hostname").getInputStream()))) {
                return reader.readLine();
            } catch (IOException ex) {
                return "unknown_host";
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new RuntimeException("Usage: java MySMTPServer <port>");
        }

        try (ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[0]))) {
            serverSocket.setReuseAddress(true);
            while (true) {
                Socket socket = serverSocket.accept();
                new MySMTPServer(socket).start();
            }
        }
    }
}