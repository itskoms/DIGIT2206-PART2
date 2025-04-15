package ca.yorku.eecs3214.mail.net;

import ca.yorku.eecs3214.mail.mailbox.MailMessage;
import ca.yorku.eecs3214.mail.mailbox.Mailbox;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class MyPOPServer extends Thread {

    private final Socket socket;
    private final BufferedReader socketIn;
    private final PrintWriter socketOut;
    private String currentUser;
    private Mailbox mailbox;
    private boolean isAuthenticated;
    private List<Integer> deletedMessages;
    private static Map<String, String> userCredentials;

    static {
        // Load user credentials from users.txt
        userCredentials = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("users.txt"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    userCredentials.put(parts[0], parts[1]);
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading user credentials: " + e.getMessage());
        }
    }

    /**
     * Initializes an object responsible for a connection to an individual client.
     *
     * @param socket The socket associated to the accepted connection.
     * @throws IOException If there is an error attempting to retrieve the socket's
     *                     information.
     */
    public MyPOPServer(Socket socket) throws IOException {
        this.socket = socket;
        this.socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.socketOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
    }

    /**
     * Handles the communication with an individual client. Must send the
     * initial welcome message, and then repeatedly read requests, process the
     * individual operation, and return a response, according to the POP3
     * protocol. Empty request lines should be ignored. Only returns if the
     * connection is terminated or if the QUIT command is issued. Must close the
     * socket connection before returning.
     */
    @Override
    public void run() {
        // Use a try-with-resources block to ensure that the socket is closed
        // when the method returns
        try (this.socket) {
            // Send welcome message
            socketOut.println("+OK POP3 server ready");
            
            String line;
            while ((line = socketIn.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                
                String[] parts = line.split("\\s+", 2);
                String command = parts[0].toUpperCase();
                String argument = parts.length > 1 ? parts[1] : null;
                
                switch (command) {
                    case "USER":
                        handleUser(argument);
                        break;
                    case "PASS":
                        handlePass(argument);
                        break;
                    case "STAT":
                        handleStat();
                        break;
                    case "LIST":
                        handleList(argument);
                        break;
                    case "RETR":
                        handleRetr(argument);
                        break;
                    case "DELE":
                        handleDele(argument);
                        break;
                    case "RSET":
                        handleRset();
                        break;
                    case "NOOP":
                        handleNoop();
                        break;
                    case "QUIT":
                        handleQuit();
                        return;
                    default:
                        socketOut.println("-ERR unknown command");
                }
            }
        } catch (IOException e) {
            System.err.println("Error in client's connection handling.");
            e.printStackTrace();
        }
    }

    private void handleUser(String username) {
        if (username == null) {
            socketOut.println("-ERR missing username");
            return;
        }
        
        // Check for extraneous parameters
        if (username.contains(" ")) {
            socketOut.println("-ERR too many parameters");
            return;
        }
        
        currentUser = username;
        socketOut.println("+OK user accepted");
    }

    private void handlePass(String password) {
        if (currentUser == null) {
            socketOut.println("-ERR no username specified");
            return;
        }
        
        if (password == null) {
            socketOut.println("-ERR missing password");
            return;
        }
        
        // Check for extraneous parameters
        if (password.contains(" ")) {
            socketOut.println("-ERR too many parameters");
            return;
        }
        
        try {
            mailbox = new Mailbox(currentUser);
            mailbox.loadMessages(password);
            isAuthenticated = true;
            deletedMessages = new ArrayList<>();
            socketOut.println("+OK user authenticated");
        } catch (Mailbox.InvalidUserException e) {
            socketOut.println("-ERR invalid username or password");
        } catch (Mailbox.MailboxNotAuthenticatedException e) {
            socketOut.println("-ERR invalid username or password");
        }
    }

    private void handleStat() {
        if (!isAuthenticated) {
            socketOut.println("-ERR not authenticated");
            return;
        }
        
        try {
            int messageCount = mailbox.size(false); // Don't include deleted messages
            long totalSize = mailbox.getTotalUndeletedFileSize(false);
            socketOut.println("+OK " + messageCount + " " + totalSize);
        } catch (Mailbox.MailboxNotAuthenticatedException e) {
            socketOut.println("-ERR not authenticated");
        }
    }

    private void handleList(String arg) {
        if (!isAuthenticated) {
            socketOut.println("-ERR not authenticated");
            return;
        }
        
        try {
            if (arg == null) {
                // List all messages
                socketOut.println("+OK");
                int messageNumber = 1;
                for (MailMessage msg : mailbox) {
                    if (!deletedMessages.contains(messageNumber)) {
                        socketOut.println(messageNumber + " " + msg.getFileSize());
                    }
                    messageNumber++;
                }
                socketOut.println(".");
            } else {
                // Check for extraneous parameters
                if (arg.contains(" ")) {
                    socketOut.println("-ERR too many parameters");
                    return;
                }
                
                // List specific message
                try {
                    int msgNum = Integer.parseInt(arg);
                    MailMessage msg = mailbox.getMailMessage(msgNum);
                    if (!deletedMessages.contains(msgNum)) {
                        socketOut.println("+OK " + msgNum + " " + msg.getFileSize());
                    } else {
                        socketOut.println("-ERR no such message");
                    }
                } catch (NumberFormatException e) {
                    socketOut.println("-ERR invalid message number");
                } catch (IndexOutOfBoundsException e) {
                    socketOut.println("-ERR no such message");
                }
            }
        } catch (Mailbox.MailboxNotAuthenticatedException e) {
            socketOut.println("-ERR not authenticated");
        }
    }

    private void handleRetr(String arg) {
        if (!isAuthenticated) {
            socketOut.println("-ERR not authenticated");
            return;
        }
        
        if (arg == null) {
            socketOut.println("-ERR missing message number");
            return;
        }
        
        // Check for extraneous parameters
        if (arg.contains(" ")) {
            socketOut.println("-ERR too many parameters");
            return;
        }
        
        try {
            int msgNum = Integer.parseInt(arg);
            MailMessage msg = mailbox.getMailMessage(msgNum);
            if (!deletedMessages.contains(msgNum)) {
                socketOut.println("+OK " + msg.getFileSize() + " octets");
                try (BufferedReader reader = new BufferedReader(new FileReader(msg.getFile()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        socketOut.println(line);
                    }
                }
                socketOut.println(".");
            } else {
                socketOut.println("-ERR no such message");
            }
        } catch (NumberFormatException e) {
            socketOut.println("-ERR invalid message number");
        } catch (IndexOutOfBoundsException e) {
            socketOut.println("-ERR no such message");
        } catch (IOException e) {
            socketOut.println("-ERR error reading message");
        } catch (Mailbox.MailboxNotAuthenticatedException e) {
            socketOut.println("-ERR not authenticated");
        }
    }

    private void handleDele(String arg) {
        if (!isAuthenticated) {
            socketOut.println("-ERR not authenticated");
            return;
        }
        
        if (arg == null) {
            socketOut.println("-ERR missing message number");
            return;
        }
        
        // Check for extraneous parameters
        if (arg.contains(" ")) {
            socketOut.println("-ERR too many parameters");
            return;
        }
        
        try {
            int msgNum = Integer.parseInt(arg);
            MailMessage msg = mailbox.getMailMessage(msgNum);
            if (!deletedMessages.contains(msgNum)) {
                msg.tagForDeletion();
                deletedMessages.add(msgNum);
                socketOut.println("+OK message " + msgNum + " deleted");
            } else {
                socketOut.println("-ERR no such message");
            }
        } catch (NumberFormatException e) {
            socketOut.println("-ERR invalid message number");
        } catch (IndexOutOfBoundsException e) {
            socketOut.println("-ERR no such message");
        } catch (Mailbox.MailboxNotAuthenticatedException e) {
            socketOut.println("-ERR not authenticated");
        }
    }

    private void handleRset() {
        if (!isAuthenticated) {
            socketOut.println("-ERR not authenticated");
            return;
        }
        
        try {
            for (MailMessage msg : mailbox) {
                msg.undelete();
            }
            deletedMessages.clear();
            socketOut.println("+OK reset state");
        } catch (Mailbox.MailboxNotAuthenticatedException e) {
            socketOut.println("-ERR not authenticated");
        }
    }

    private void handleNoop() {
        if (!isAuthenticated) {
            socketOut.println("-ERR not authenticated");
            return;
        }
        
        socketOut.println("+OK");
    }

    private void handleQuit() {
        if (isAuthenticated) {
            try {
                mailbox.deleteMessagesTaggedForDeletion();
            } catch (Exception e) {
                // Ignore errors during cleanup
            }
        }
        socketOut.println("+OK POP3 server signing off");
    }

    /**
     * Main process for the POP3 server. Handles the argument parsing and
     * creates a listening server socket. Repeatedly accepts new connections
     * from individual clients, creating a new server instance that handles
     * communication with that client in a separate thread.
     *
     * @param args The command-line arguments.
     * @throws IOException In case of an exception creating the server socket or
     *                     accepting new connections.
     */
    public static void main(String[] args) throws IOException {

        if (args.length != 1) {
            throw new RuntimeException(
                    "This application must be executed with exactly one argument, the listening port.");
        }

        try (ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[0]))) {
            serverSocket.setReuseAddress(true);

            System.out.println("Waiting for connections on port " + serverSocket.getLocalPort() + "...");
            // noinspection InfiniteLoopStatement
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Accepted a connection from " + socket.getRemoteSocketAddress());
                try {
                    MyPOPServer handler = new MyPOPServer(socket);
                    handler.start();
                } catch (IOException e) {
                    System.err.println("Error setting up an individual client's handler.");
                    e.printStackTrace();
                }
            }
        }
    }
}