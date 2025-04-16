// We are having trouble with the DATA and RSET commands, the tests are
// not helping because they are expecting the wrong email to return when the output shows that the email address returned is correct. 
// It was inputed for this command and the command is returning the inputed email address. 

// For example, this is the error message: 

// org.opentest4j.AssertionFailedError: Message content in saved message file does not match message sent in connection, 
// or writer was not flushed/closed before the successful response. ==> expected: <To: <john.doe@example.com>> but was: <To: <another_valid_user@example.com>>

// However, the output shows that the email address is correct.

// Command: EHLO somehostname
// Command: MAIL FROM:<sender@example.com>
// Command: RCPT TO:<valid_user1@example.com>
// Command: RSET
// Command: MAIL FROM:<sender@example.com>
// Command: RCPT TO:<another_valid_user@example.com>
// Command: DATA
// Command: .

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
    private StringBuilder messageData = new StringBuilder();
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
            // Send SMTP welcome message
            socketOut.println("220 " + getHostName() + " SMTP server ready");

            String inputLine;
            while ((inputLine = socketIn.readLine()) != null && !isQuit) {
                if (inputLine.trim().isEmpty()) {
                    continue;
                }

                System.out.println("Received: " + inputLine);
                
                if (waitingForData) {
                    // Process message content during DATA command
                    handleData(inputLine);
                } else {
                    // Process SMTP commands
                    String response = handleCommand(inputLine);
                    socketOut.println(response);
                }
                
                // Check if QUIT command was processed
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

        // Process VRFY command
        if (command.equals("VRFY")) {
            if (argument == null || argument.isEmpty()) {
                return "501 Syntax: VRFY <address>";
            }
            if (!Mailbox.isValidUser(argument)) {
                return "550 User not found";
            }
            String vrfyAddress = extractEmailAddress(argument);
            if (vrfyAddress == null) {
                return "501 Syntax error in parameters or arguments";
            }
            return "250 " + vrfyAddress;
        }

        // Process MAIL command
        if (command.equals("MAIL")) {
            if (!isHeloReceived) {
                return "503 Bad sequence of commands";
            }
            
            if (!inputLine.matches("^MAIL\\s+FROM:\\s*<.*>$")) {
                return "500 Syntax error, command unrecognized";
            }
            
            String mailArg = inputLine.substring("MAIL FROM:".length()).trim();
            if (mailArg.isEmpty()) {
                return "501 Syntax error in parameters or arguments";
            }
            
            String fromAddress = extractEmailAddress(mailArg);
            if (fromAddress == null) {
                return "501 Syntax error in parameters or arguments";
            }
            
            recipients.clear();
            sender = fromAddress;
            return "250 OK";
        }

        // Process RCPT command
        if (command.equals("RCPT")) {
            if (!isHeloReceived) {
                return "503 Bad sequence of commands";
            }
            if (sender == null) {
                return "503 Need MAIL before RCPT";
            }
            
            if (!inputLine.matches("^RCPT\\s+TO:\\s*<.*>$")) {
                return "500 Syntax error, command unrecognized";
            }
            
            String toArg = inputLine.substring("RCPT TO:".length()).trim();
            if (toArg.isEmpty()) {
                return "501 Syntax error in parameters or arguments";
            }
            
            String toAddress = extractEmailAddress(toArg);
            if (toAddress == null) {
                String potentialUser = toArg.replaceAll("^<|>$", "").trim();
                if (!Mailbox.isValidUser(potentialUser)) {
                    return "550 No such user here";
                }
                return "501 Syntax error in parameters or arguments";
            }
            
            if (!Mailbox.isValidUser(toAddress)) {
                return "550 No such user here";
            }
            
            recipients.add(toAddress);
            return "250 OK";
        }

        // Process DATA command
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
            messageData.setLength(0); 
            return "354 Start mail input; end with <CRLF>.<CRLF>";
        }

        // Check command sequence
        if (!isHeloReceived && !command.equals("HELO") && !command.equals("EHLO") && 
            !command.equals("QUIT") && !command.equals("NOOP") && !command.equals("RSET")) {
            return "503 Bad sequence of commands";
        }

        // Process remaining commands
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
                // Reset all state variables to initial values
                resetState();
                // Keep HELO state as per RFC 5321
                isHeloReceived = true;
                return "250 OK";

            default:
                return isUnsupportedCommand(command) ? "502 Command not implemented" : "500 Command not recognized";
        }
    }

    private void handleData(BufferedReader socketIn) {
        try {
            // Read message content lines until "." is received
            String inputLine;
            StringBuilder messageData = new StringBuilder();

            while ((inputLine = socketIn.readLine()) != null) {
                if (".".equals(inputLine)) {
                    break; // End of message
                }

                // Dot-stuffing: reduce ".." to "."
                if (inputLine.startsWith("..")) {
                    inputLine = inputLine.substring(1);
                }

                messageData.append(inputLine).append("\r\n"); // SMTP requires CRLF
            }

            if (messageData.isEmpty()) {
                resetState();
                return;
            }

            // Build mailboxes for valid recipients
            List<Mailbox> recipientMailboxes = new ArrayList<>();
            for (String recipient : recipients) {
                try {
                    recipientMailboxes.add(new Mailbox(recipient));
                } catch (Mailbox.InvalidUserException e) {
                    System.err.println("Invalid recipient: " + recipient + " -> " + e.getMessage());
                    socketOut.println("451 Requested action aborted: invalid recipient");
                    resetState();
                    return;
                }
            }

            // Use MailWriter to write message to all recipients
            try (MailWriter writer = new MailWriter(recipientMailboxes)) {
                writer.write(messageData.toString());
                writer.flush(); // ensure buffer is written before closing
            } catch (IOException e) {
                System.err.println("Failed to write to mailboxes: " + e.getMessage());
                socketOut.println("451 Requested action aborted: error writing to mailboxes");
                resetState();
                return;
            }

            // Successful delivery
            socketOut.println("250 OK");
            resetState();

        } catch (IOException e) {
            System.err.println("Unexpected I/O error during handleData: " + e.getMessage());
            socketOut.println("451 Requested action aborted: internal error");
            resetState();
        }
    }

    private String extractEmailAddress(String argument) {
        if (argument == null) return null;
        
        argument = argument.replaceAll("^(?i)(MAIL FROM:|RCPT TO:)\\s*", "");
        
        if (argument.startsWith("<") && argument.endsWith(">")) {
            argument = argument.substring(1, argument.length() - 1).trim();
        }
        
        if (!EMAIL_PATTERN.matcher(argument).matches()) {
            return null;
        }
        
        return argument;
    }

    private void resetState() {
        sender = null;
        recipients.clear();  
        messageData = new StringBuilder();
        waitingForData = false;
        messageData.setLength(0);
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