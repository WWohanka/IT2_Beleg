/* ------------------
   Client
   usage: java Client [Server hostname] [Server RTSP listening port] [Video file requested]

   Default: java Client null 5554 movie.mjpeg
   ---------------------- */

import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;
import javax.swing.Timer;

public class Client {

    //GUI
    //----
    JFrame f = new JFrame("Client");
    JButton setupButton = new JButton("Setup");
    JButton playButton = new JButton("Play");
    JButton pauseButton = new JButton("Pause");
    JButton tearButton = new JButton("Teardown");
    // JButton akt = new JButton("Aktualisieren");	
    JButton optionsButton = new JButton("Options");
    JPanel mainPanel = new JPanel();
    JPanel buttonPanel = new JPanel();
    JLabel iconLabel = new JLabel();

    JLabel ilabel = new JLabel();

    ImageIcon icon;
    int sentpackets = 0, lostpackets = 0, receivedpackets = 0;
    float packetlossrate = 0.0f, datarate = 0.0f;


    //RTP variables:
    //----------------
    DatagramPacket rcvdp; //UDP packets received from the server
    DatagramSocket RTPsocket; //socket to be used to send and receive UDP packets
    static int RTP_RCV_PORT = 5554; //port where the client will receive the RTP packets


    Timer timer, timer2; //timer used to receive data from the UDP socket
    byte[] buf; //buffer used to store data received from the server 

    //RTSP variables
    //----------------
    //rtsp states 
    final static int INIT = 0;
    final static int READY = 1;
    final static int PLAYING = 2;
    static int state; //RTSP state == INIT or READY or PLAYING
    Socket RTSPsocket; //socket used to send/receive RTSP messages
    //input and output stream filters
    static BufferedReader RTSPBufferedReader;
    static BufferedWriter RTSPBufferedWriter;
    static String VideoFileName; //video file to request to the server
    int RTSPSeqNb = 0; //Sequence number of RTSP messages within the session
    int RTSPid = 0; //ID of the RTSP session (given by the RTSP Server)
    int seqNumber;
    final static String CRLF = "\r\n";
    static int counter= 0;
    //Video constants:
    //------------------

    static int FEC_TYPE = 127;				// RTP payload type for FEC





    List<RTPpacket> fec_buffer = new ArrayList<RTPpacket>();
    FECdecode decode;


    //--------------------------
    //Constructor
    //--------------------------
    public Client() {

        //build GUI
        //--------------------------
        //Frame
        f.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                System.exit(0);
            }
        });

        //Label 
        buttonPanel.setLayout(new GridLayout(1, 0));
        buttonPanel.add(setupButton);
        buttonPanel.add(playButton);
        buttonPanel.add(pauseButton);
        buttonPanel.add(tearButton);
        buttonPanel.add(optionsButton);
        // buttonPanel.add(akt); 
        setupButton.addActionListener(new setupButtonListener());
        playButton.addActionListener(new playButtonListener());
        pauseButton.addActionListener(new pauseButtonListener());
        tearButton.addActionListener(new tearButtonListener());
        optionsButton.addActionListener(new optionsButtonListener());

        //Image display label
        iconLabel.setIcon(null);

        //frame layout
        mainPanel.setLayout(null);
        mainPanel.add(iconLabel);
        mainPanel.add(buttonPanel);

        iconLabel.setBounds(0, 0, 380, 280);
        buttonPanel.setBounds(0, 280, 380, 50);

        //Label und Textfeld setzen    
        //ilabel.setText("<html> gesendete Packete: " + sentpackets + " <br/> verlorene Packete: " + lostpackets + " <br/> Packetverlustrate: " + packetlossrate + " <br/> Datenrate: " + datarate + "<br/> </html>");
        ilabel.setBounds(400, 30, 300, 400);
        //Textfield unf Label hinzufügen
        mainPanel.add(ilabel);

        f.getContentPane().add(mainPanel, BorderLayout.CENTER);
        f.setSize(new Dimension(600, 370));
        f.setVisible(true);

        //init timer
        //--------------------------
        timer = new Timer(20, new timerListener());
        timer.setInitialDelay(0);
        timer.setCoalesce(true);

        timer2 = new Timer(1000, new timerListener());
        timer2.setInitialDelay(0);
        timer2.setCoalesce(true);

        buf = new byte[15000];

        decode = new FECdecode(fec_buffer);
    }

    //------------------------------------
    //main
    //------------------------------------
    public static void main(String argv[]) throws Exception {
        Client theClient = new Client();

        //get server RTSP port and IP address from the command line
        //------------------
        int RTSP_server_port = Integer.parseInt(argv[1]);
        //int RTSP_server_port= 5554;
        String ServerHost = argv[0];
        
        InetAddress ServerIPAddr = InetAddress.getByName(ServerHost);
        //InetAddress ServerIPAddr = InetAddress.getByName(null);

        //get video filename to request:
        VideoFileName = argv[2];
        //VideoFileName = "movie.mjpeg";

        //Establish a TCP connection with the server to exchange RTSP messages
        //------------------
        theClient.RTSPsocket = new Socket(ServerIPAddr, RTSP_server_port);

        //Set input and output stream filters:
        RTSPBufferedReader = new BufferedReader(new InputStreamReader(theClient.RTSPsocket.getInputStream()));
        RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theClient.RTSPsocket.getOutputStream()));

        //init RTSP state:
        state = INIT;
    }

    //------------------------------------
    //Handler for buttons
    //------------------------------------
    class setupButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {

            //System.output.println("Setup Button pressed !");
            if (state == INIT) {
                //Init non-blocking RTPsocket that will be used to receive data
                try {

                    RTPsocket = new DatagramSocket(RTP_RCV_PORT);
                    RTPsocket.setSoTimeout(5);

                } catch (SocketException se) {
                    System.out.println("Socket exception: " + se);
                    System.exit(0);
                }

                //init RTSP sequence number
                RTSPSeqNb = 1;
///////////////////////////////////////////////////////////////	 
                seqNumber = -1;
///////////////////////////////////////////////////////////////					
                //Send SETUP message to the server
                send_RTSP_request("SETUP");

                //Wait for the response 
                if (parse_server_response() != 200) {
                    System.out.println("Invalid Server Response");
                } else {
//--------------------------------------------------
                    state = READY;
                    System.out.println("New RTSP state: READY");
//--------------------------------------------------
                }
            }
        }
    }

    //Handler for Play button
    //-----------------------
    class playButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {

            System.out.println("Play Button pressed !");

            if (state == READY) {
                //--------------------------------------------
                RTSPSeqNb++;
                //-----------------------
                //Send PLAY message to the server
                send_RTSP_request("PLAY");

                //Wait for the response 
                if (parse_server_response() != 200) {
                    System.out.println("Invalid Server Response");
                } else {
//--------------------------------------------------
                    //change RTSP state and print output new state
                    state = PLAYING;
                    System.out.println("New RTSP state: Playing ");
//--------------------------------------------------

                    //start the timer
                    timer.start();
                }
            }//else if state != READY then do nothing
        }
    }

    //Handler for Pause button
    //-----------------------
    class pauseButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {

            System.out.println("Pause Button pressed !");

            if (state == PLAYING) {
                //increase RTSP sequence number
//--------------------------------
                RTSPSeqNb++;
//--------------------------------

                //Send PAUSE message to the server
                send_RTSP_request("PAUSE");

                //Wait for the response 
                if (parse_server_response() != 200) {
                    System.out.println("Invalid Server Response");
                } else {
//------------------------------------------------------------		  
                    //change RTSP state and print output new state
                    state = READY;

                    System.out.println("New RTSP state: ready");
//------------------------------------------------------------ 

                    //stop the timer
                    timer.stop();
                }
            }
            //else if state != PLAYING then do nothing
        }
    }

    //Handler for Teardown button
    //-----------------------
    class tearButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {

            System.out.println("Teardown Button pressed !");
            //increase RTSP sequence number
            //--------------------------------
            RTSPSeqNb++;
            //--------------------------------
            send_RTSP_request("TEARDOWN");

            //Wait for the response 
            if (parse_server_response() != 200) {
                System.out.println("Invalid Server Response");
            } else {
//------------------------------------------------------
                state = INIT;
                System.out.println("New RTSP state:init");
//------------------------------------------------------
                //stop the timer
                timer.stop();

                //exit
                System.exit(0);
            }
        }
    }

    //------------------------------------
    //Handler for timer
    //------------------------------------
    class timerListener implements ActionListener {
        public void actionPerformed(ActionEvent e) {

            //Construct a DatagramPacket to receive data from the UDP socket
            rcvdp = new DatagramPacket(buf, buf.length);


            try{
                counter++;

                //receive the DP from the socket:
                RTPsocket.receive(rcvdp);

                //create an RTPpacket object from the DP
                RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());

                //create an FEC object from the DP
                boolean IsFecPacket;
                IsFecPacket = rtp_packet.getpayloadtype() == FEC_TYPE;
                if( IsFecPacket )
                {

                    //check for lost packages
                    decode.rcvfec(new FECpacket(rcvdp.getData(), rcvdp.getLength()));


                }else{

                    //Buffer for incoming Packets
                    decode.rcvdata(rtp_packet);


                }
                if (fec_buffer.size()>0){

                    RTPpacket corrected = fec_buffer.get(0);
                    int payload_length = corrected.payload_size;
                    byte[] payload = new byte[payload_length];
                    corrected.getpayload(payload);
                    //get an Image object from the payload bitstream
                    Toolkit toolkit = Toolkit.getDefaultToolkit();
                    Image image = toolkit.createImage(payload, 0, payload_length);

                    //display the image as an ImageIcon object
                    icon = new ImageIcon(image);
                    iconLabel.setIcon(icon);

                    fec_buffer.clear();
                }



                if(rtp_packet.getsequencenumber( ) > decode.getReceived())
                {
                    receivedpackets = decode.getReceived();

                    sentpackets=rtp_packet.getsequencenumber();

                    lostpackets=decode.getMissing();

                    datarate=receivedpackets/(float)rtp_packet.gettimestamp()*1000;

                    packetlossrate= lostpackets/(float)sentpackets;

                    ilabel.setText(	"<html> Erhaltene Packete: "+receivedpackets+" <br/> verlorene Packete: "+lostpackets+" " +
                            "<br/> Verlustrate: "+packetlossrate+" <br/> Datenrate: "+datarate+"<br/> " +
                            "Wiederhergestellte Packete: "+ decode.getRecovered() + "<br/> Pakete im Puffer:"+decode.getMediaBuffered()+"</html>");
                }


            }
            catch (InterruptedIOException iioe){
                //System.output.println("Nothing to read");
            }
            catch (IOException ioe) {
                System.out.println("Exception caught: "+ioe);
            }
        }
    }




    private int parse_server_response() {
        int reply_code = 0;

        try {
            //parse status line and extract the reply_code:
            String StatusLine = RTSPBufferedReader.readLine();
            System.out.println("RTSP Client - Received from Server:");
            System.out.println(StatusLine);

            StringTokenizer tokens = new StringTokenizer(StatusLine);
            tokens.nextToken(); //skip over the RTSP version
            reply_code = Integer.parseInt(tokens.nextToken());

            if (reply_code == 200) {
                String SeqNumLine = RTSPBufferedReader.readLine();
                System.out.println(SeqNumLine);

                String SessionLine = RTSPBufferedReader.readLine();
                System.out.println(SessionLine);

                tokens = new StringTokenizer(SessionLine);
                tokens.nextToken(); //skip over the Session:
                RTSPid = Integer.parseInt(tokens.nextToken());
            }
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }

        return (reply_code);
    }

    //------------------------------------
    //Send RTSP Request
    //------------------------------------
    private void send_RTSP_request(String request_type) {
        try {
            //Use the RTSPBufferedWriter to write to the RTSP socket

            //-----------------------------------   	
            //write the request line:
            RTSPBufferedWriter.write(request_type + " " + VideoFileName + " RTSP/1.0 " + CRLF);
            //write the CSeq line: 
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);
            if (request_type.equals("SETUP")) //RTP_RCV_PORT
            //C: Transport: RTP/UDP; client_port= 25000
            {
                RTSPBufferedWriter.write("Transport: RTP/UDP; client_port= " + RTP_RCV_PORT + CRLF);
            } else {
                RTSPBufferedWriter.write("Session: " + RTSPid + CRLF);
            }

            System.out.println(request_type + " " + VideoFileName + " RTSP/1.0 " + CRLF);
            System.out.println("CSeq: " + RTSPSeqNb + CRLF);
            System.out.println("Transport: RTP/UDP; client_port= " + RTP_RCV_PORT + CRLF);
            System.out.println("Session: " + RTSPid + CRLF);

//-----------------------------------
            RTSPBufferedWriter.flush();
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }
    }

    class optionsButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {

            //increase RTSP sequence number
            RTSPSeqNb++;

            //Send OPTIONS message to the server
            send_RTSP_options_request("OPTIONS", "implicit-play", "Proxy-Require: gzipped-messages");

            //Wait for the response 
            if (parse_server_options_response() != 200) {
                System.out.println("Invalid Server Response");
            } else {
                 System.out.println("Got OPTIONS");

            }
        }
    }

    private int parse_server_options_response() {

        int reply_code = 0;

        try {
            //parse status line and extract the reply_code:
            String StatusLine = RTSPBufferedReader.readLine();
            //System.output.println("RTSP Client - Received from Server:");
            System.out.println(StatusLine);

            StringTokenizer tokens = new StringTokenizer(StatusLine);
            tokens.nextToken(); //skip over the RTSP version
            reply_code = Integer.parseInt(tokens.nextToken());

            //if reply code is OK get and print the 2 other lines
            if (reply_code == 200) {
                String SeqNumLine = RTSPBufferedReader.readLine();
                System.out.println(SeqNumLine);

                String SessionLine = RTSPBufferedReader.readLine();
                System.out.println(SessionLine);

                //if state == INIT gets the Session Id from the SessionLine
                tokens = new StringTokenizer(SessionLine, ":");
                tokens.nextToken(); //skip over the Session:
            }
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }

        return (reply_code);
    }

    private void send_RTSP_options_request(String request_type, String feature, String parameterline) {
        try {
            //Use the RTSPBufferedWriter to write to the RTSP socket

            RTSPBufferedWriter.write(request_type + " " + VideoFileName + " RTSP/1.0" + CRLF);
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);

            RTSPBufferedWriter.write("Require: " + feature + CRLF);
            RTSPBufferedWriter.write(parameterline + CRLF);
            System.out.println(parameterline);

            RTSPBufferedWriter.flush();
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }
    }

}

//end of Class Client

