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

    // Email address pattern for validation
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /**
     * Initializes an object responsible for a connection to an individual client.
     *
     * @param socket The socket associated to the accepted connection.
     * @throws IOException If there is an error attempting to retrieve the socket's information.
     */
    public MySMTPServer(Socket socket) throws IOException {
        this.socket = socket;
        this.socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.socketOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
    }

    /**
     * Handles the communication with an individual client. Must send the initial welcome message, and then repeatedly
     * read requests, process the individual operation, and return a response, according to the SMTP protocol. Empty
     * request lines should be ignored. Only returns if the connection is terminated or if the QUIT command is issued.
     * Must close the socket connection before returning.
     */
    @Override
    public void run() {
        try (this.socket) {
            socketOut.println("220 " + getHostName() + " SMTP server ready");

            String inputLine;
            while ((inputLine = socketIn.readLine()) != null && !isQuit) {
                if (inputLine.trim().isEmpty()) {
                    continue;
                }

                System.out.println("Received: " + inputLine);
                String response = handleCommand(inputLine);
                socketOut.println(response);

                if (waitingForData) {
                    handleData(inputLine);
                }
                
                if (isQuit) {
                    socket.close();
                    return;
                }
            }

        } catch (IOException e) {
            System.err.println("Error in client's connection handling.");
            e.printStackTrace();
        }
    }

    private boolean isUnsupportedCommand(String command) {
        // List of known SMTP commands that we don't support
        String[] unsupportedCommands = {
            "EXPN", "HELP", "AUTH", "STARTTLS", "TURN", "SOML", "SEND", "SAML"
        };
        
        for (String unsupported : unsupportedCommands) {
            if (command.equalsIgnoreCase(unsupported)) {
                return true;
            }
        }
        return false;
    }

    private String handleCommand(String inputLine) {
        String[] parts = inputLine.trim().split("\\s+", 2);
        String command = parts[0].toUpperCase();
        String argument = parts.length > 1 ? parts[1].trim() : null;

        // Special handling for VRFY - always allowed
        if (command.equals("VRFY")) {
            if (argument == null || argument.isEmpty()) {
                return "501 Syntax: VRFY <address>";
            }
            String vrfyAddress = extractEmailAddress(argument);
            if (vrfyAddress == null) {
                return "501 Syntax error in parameters or arguments";
            }
            return Mailbox.isValidUser(vrfyAddress) ? "250 " + vrfyAddress : "550 User not found";
        }

        // Check for HELO/EHLO requirement for MAIL command
        if (command.equals("MAIL")) {
            if (!isHeloReceived) {
                return "503 Bad sequence of commands";
            }
            if (!inputLine.toUpperCase().startsWith("MAIL FROM:")) {
                return "501 Syntax: MAIL FROM:<address>";
            }
            String mailArg = inputLine.substring("MAIL FROM:".length()).trim();
            String fromAddress = extractEmailAddress(mailArg);
            if (fromAddress == null) {
                return "501 Syntax error in parameters or arguments";
            }
            sender = fromAddress;
            return "250 OK";
        }

        // Check for RCPT command
        if (command.equals("RCPT")) {
            if (!isHeloReceived) {
                return "503 Bad sequence of commands";
            }
            if (sender == null) {
                return "503 Need MAIL before RCPT";
            }
            if (!inputLine.toUpperCase().startsWith("RCPT TO:")) {
                return "501 Syntax: RCPT TO:<address>";
            }
            String toAddress = extractEmailAddress(argument);
            if (toAddress == null) {
                return "501 Syntax error in parameters or arguments";
            }
            if (!Mailbox.isValidUser(toAddress)) {
                return "550 No such user here";
            }
            recipients.add(toAddress);
            return "250 OK";
        }

        // Check for DATA command
        if (command.equals("DATA")) {
            if (!isHeloReceived) {
                return "503 Bad sequence of commands";
            }
            if (sender == null) {
                return "503 Need MAIL before DATA";
            }
            if (recipients.isEmpty()) {
                return "503 Need RCPT before DATA";
            }
            waitingForData = true;
            return "354 Start mail input; end with <CRLF>.<CRLF>";
        }

        if (!isHeloReceived && !command.equals("HELO") && !command.equals("EHLO") && 
            !command.equals("QUIT") && !command.equals("NOOP")) {
            return "503 Bad sequence of commands";
        }

        switch (command) {
            case "HELO":
            case "EHLO":
                if (argument == null || argument.isEmpty()) {
                    return "501 Syntax: HELO/EHLO hostname";
                }
                isHeloReceived = true;
                return "250 " + getHostName() + " Hello " + argument;

            case "NOOP":
                return "250 OK";

            case "QUIT":
                isQuit = true;
                return "221 " + getHostName() + " closing connection";

            case "RSET":
                resetState();
                return "250 OK";

            default:
                return isUnsupportedCommand(command) ? "502 Command not implemented" : "500 Command not recognized";
        }
    }

    private void handleData(String inputLine) {
        if (inputLine.equals(".")) {
            try {
                // Create mailboxes for recipients and write the message
                List<Mailbox> recipientMailboxes = new ArrayList<>();
                for (String recipient : recipients) {
                    try {
                        recipientMailboxes.add(new Mailbox(recipient));
                    } catch (Mailbox.InvalidUserException e) {
                        System.err.println("Error creating mailbox for recipient " + recipient + ": " + e.getMessage());
                        socketOut.println("451 Requested action aborted: invalid recipient");
                        return;
                    }
                }
                
                // Write the complete message including headers
                try (MailWriter writer = new MailWriter(recipientMailboxes)) {
                    // Write the message headers
                    writer.write("From: " + sender + "\r\n");
                    writer.write("To: " + String.join(", ", recipients) + "\r\n");
                    writer.write("Date: " + new java.util.Date() + "\r\n");
                    writer.write("\r\n"); // Empty line separating headers from body
                    
                    // Write the message body
                    writer.write(messageData.toString());
                }
                
                // Reset state after successful message storage
                waitingForData = false;
                messageData.setLength(0);
                socketOut.println("250 OK");
            } catch (Exception e) {
                System.err.println("Error saving message: " + e.getMessage());
                socketOut.println("451 Requested action aborted: local error in processing");
            }
        } else {
            // Handle dot-stuffing (RFC 5321 Section 4.5.2)
            if (inputLine.startsWith("..")) {
                inputLine = inputLine.substring(1);
            }
            messageData.append(inputLine).append("\r\n");
        }
    }

    private String extractEmailAddress(String argument) {
        if (argument == null) return null;
        
        // Remove MAIL FROM: or RCPT TO: prefix if present
        argument = argument.replaceAll("^(?i)(MAIL FROM:|RCPT TO:)\\s*", "");
        
        // Extract email address from <address> format
        if (argument.startsWith("<") && argument.endsWith(">")) {
            argument = argument.substring(1, argument.length() - 1);
        }
        
        // Validate email format
        if (!EMAIL_PATTERN.matcher(argument).matches()) {
            return null;
        }
        
        return argument;
    }

    private void resetState() {
        sender = null;
        recipients.clear();
        messageData.setLength(0);
        waitingForData = false;
    }

    /**
     * Retrieves the name of the current host. Used in the response of commands like HELO and EHLO.
     * @return A string corresponding to the name of the current host.
     */
    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            try (BufferedReader reader = Runtime.getRuntime().exec(new String[] {"hostname"}).inputReader()) {
                return reader.readLine();
            } catch (IOException ex) {
                return "unknown_host";
            }
        }
    }

    /**
     * Main process for the SMTP server. Handles the argument parsing and creates a listening server socket. Repeatedly
     * accepts new connections from individual clients, creating a new server instance that handles communication with
     * that client in a separate thread.
     *
     * @param args The command-line arguments.
     * @throws IOException In case of an exception creating the server socket or accepting new connections.
     */
    public static void main(String[] args) throws IOException {

        if (args.length != 1) {
            throw new RuntimeException("This application must be executed with exactly one argument, the listening port.");
        }

        try (ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[0]))) {
            serverSocket.setReuseAddress(true);
            System.out.println("Waiting for connections on port " + serverSocket.getLocalPort() + "...");
            //noinspection InfiniteLoopStatement
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Accepted a connection from " + socket.getRemoteSocketAddress());
                try {
                    MySMTPServer handler = new MySMTPServer(socket);
                    handler.start();
                } catch (IOException e) {
                    System.err.println("Error setting up an individual client's handler.");
                    e.printStackTrace();
                }
            }
        }
    }
}