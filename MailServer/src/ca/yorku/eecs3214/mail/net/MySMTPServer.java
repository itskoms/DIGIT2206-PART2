package ca.yorku.eecs3214.mail.net;

import ca.yorku.eecs3214.mail.mailbox.MailWriter;
import ca.yorku.eecs3214.mail.mailbox.Mailbox;

import java.io.*;
import java.net.*;
import java.util.*;

public class MySMTPServer extends Thread {

    private final Socket clientSocket;
    private final BufferedReader in;
    private final PrintWriter out;

    public MySMTPServer(Socket clientSocket) throws IOException {
        this.clientSocket = clientSocket;
        this.in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        this.out = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream()), true);
    }

    @Override
    public void run() {
        try (clientSocket) {
            out.println("220 " + getServerName() + " Simple Mail Transfer Service Ready");

            String fromAddress = null;
            List<Mailbox> recipientList = new ArrayList<>();
            boolean inDataMode = false;
            boolean greeted = false;

            String inputLine;

            while ((inputLine = in.readLine()) != null) {
                inputLine = inputLine.trim();
                if (inputLine.isEmpty()) continue;

                String command = inputLine.toUpperCase();

                if (inDataMode) {
                    StringBuilder messageBody = new StringBuilder();
                    messageBody.append(inputLine).append("\r\n");

                    while ((inputLine = in.readLine()) != null && !inputLine.equals(".")) {
                        messageBody.append(inputLine).append("\r\n");
                    }

                    try (MailWriter writer = new MailWriter(recipientList)) {
                        writer.write(messageBody.toString());
                    } catch (IOException e) {
                        out.println("451 Local error encountered while processing");
                        continue;
                    }

                    out.println("250 Message received");
                    fromAddress = null;
                    recipientList.clear();
                    inDataMode = false;
                    continue;
                }

                if (command.startsWith("HELO ")) {
                    greeted = true;
                    out.println("250 " + getServerName());
                } else if (command.startsWith("EHLO ")) {
                    greeted = true;
                    out.println("250-" + getServerName());
                    out.println("250 HELP");
                } else if (command.startsWith("MAIL FROM:")) {
                    if (!greeted) {
                        out.println("503 Please greet the server first");
                        continue;
                    }
                    if (!inputLine.matches("(?i)^MAIL FROM:\\s*<[^<>@]+@[^<>@]+>$")) {
                        out.println("501 Malformed address format");
                        continue;
                    }
                    fromAddress = extractAddress(inputLine);
                    recipientList.clear();
                    out.println("250 Sender OK");
                } else if (command.startsWith("RCPT TO:")) {
                    if (fromAddress == null) {
                        out.println("503 Sender not specified");
                        continue;
                    }
                    if (!inputLine.matches("(?i)^RCPT TO:\\s*<[^<>@]+@[^<>@]+>$")) {
                        out.println("501 Invalid recipient address");
                        continue;
                    }
                    String recipient = extractAddress(inputLine);
                    if (!Mailbox.isValidUser(recipient)) {
                        out.println("550 Unknown recipient");
                        continue;
                    }
                    try {
                        recipientList.add(new Mailbox(recipient));
                        out.println("250 Recipient accepted");
                    } catch (Mailbox.InvalidUserException e) {
                        out.println("550 Unknown recipient");
                    }
                } else if (command.equals("DATA")) {
                    if (fromAddress == null || recipientList.isEmpty()) {
                        out.println("503 Incomplete transaction, MAIL FROM and RCPT TO required");
                        continue;
                    }
                    out.println("354 Ready to receive email content; end with <CRLF>.<CRLF>");
                    inDataMode = true;
                } else if (command.equals("RSET")) {
                    fromAddress = null;
                    recipientList.clear();
                    inDataMode = false;
                    out.println("250 State reset");
                } else if (command.equals("NOOP")) {
                    out.println("250 Still here");
                } else if (command.equals("QUIT")) {
                    out.println("221 " + getServerName() + " closing connection");
                    break;
                } else if (command.startsWith("VRFY ")) {
                    String userToVerify = inputLine.substring(5).trim();
                    if (Mailbox.isValidUser(userToVerify)) {
                        out.println("250 " + userToVerify + " found");
                    } else {
                        out.println("550 User not found");
                    }
                } else {
                    out.println("501 Unknown or unsupported command");
                }
            }
        } catch (IOException e) {
            System.err.println("Connection error while serving client.");
        }
    }

    private static String extractAddress(String line) {
        int start = line.indexOf('<') + 1;
        int end = line.indexOf('>');
        return line.substring(start, end);
    }

    private static String getServerName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    Runtime.getRuntime().exec("hostname").getInputStream()))) {
                return reader.readLine();
            } catch (IOException ex) {
                return "unknown_host";
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: java MySMTPServer <port>");
        }

        int port = Integer.parseInt(args[0]);

        try (ServerSocket server = new ServerSocket(port)) {
            server.setReuseAddress(true);
            System.out.println("SMTP server started on port " + port);
            while (true) {
                Socket client = server.accept();
                try {
                    new MySMTPServer(client).start();
                } catch (IOException e) {
                    System.err.println("Error initializing client handler.");
                    e.printStackTrace();
                }
            }
        }
    }
}