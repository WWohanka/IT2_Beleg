/* ------------------
   Server
   usage: java Server [RTSP listening port] [loss rate] [FEC size k]
   ---------------------- */

import java.io.*;
import java.net.*;
import java.awt.*;
import java.util.*;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;
import javax.swing.Timer;

public class Server extends JFrame implements ActionListener {


    String requestedFeature;
    String requestedFeatureParameter;
    static float LostRate;
    private Random Rand = new Random();
    //RTP variables:
    //----------------
    DatagramSocket RTPsocket; //socket to be used to send and receive UDP packets
    DatagramPacket senddp; //UDP packets containing the video frames

    InetAddress ClientIPAddr; //Client IP address
    int RTP_dest_port = 0; //destination port for RTP packets  (given by the RTSP Client)

    //GUI:
    //----------------
    //JFram e f = new JFrame("Server");
    JLabel label;
    JTextField TF = new JTextField("0", 15);
    JPanel LabelPanel = new JPanel();
    JLabel ilabel = new JLabel();


    //Video variables:
    //----------------
    int imagenb = 0; //image nb of the image currently transmitted
    VideoStream video; //VideoStream object used to access video frames
    static int MJPEG_TYPE = 26; //RTP payload type for MJPEG video
    static int FRAME_PERIOD = 40; //Frame period of the video to stream, in ms
    static int VIDEO_LENGTH = 500; //length of the video in frames

    Timer timer; //timer used to send the images at the video frame rate
    byte[] buf; //buffer used to store the images to send to the client 

    //RTSP variables
    //----------------
    //rtsp states
    final static int INIT = 0;
    final static int READY = 1;
    final static int PLAYING = 2;
    //rtsp message types
    final static int SETUP = 3;
    final static int PLAY = 4;
    final static int PAUSE = 5;
    final static int TEARDOWN = 6;
    final static int OPTIONS = 7;

    static int state; //RTSP Server state == INIT or READY or PLAY
    Socket RTSPsocket; //socket used to send/receive RTSP messages
    //input and output stream filters
    static BufferedReader RTSPBufferedReader;
    static BufferedWriter RTSPBufferedWriter;
    static String VideoFileName; //video file requested from the client
    static int RTSP_ID = 123456; //ID of the RTSP session
    int RTSPSeqNb = 0; //Sequence number of RTSP messages within the session
    static int fec_size = 0;

    final static String CRLF = "\r\n";

    List<RTPpacket> packets = new ArrayList<RTPpacket>();

    //--------------------------------
    //Constructor
    //--------------------------------
    public Server() {

        //init Frame
        super("Server");

        //init Timer
        timer = new Timer(FRAME_PERIOD, this);
        timer.setInitialDelay(0);
        timer.setCoalesce(true);

        //allocate memory for the sending buffer
        buf = new byte[45000];

        //Handler to close the main window
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                //stop the timer and exit
                timer.stop();
                System.exit(0);
            }
        });

        //GUI:
        ////////////////////////////////////////////////////////////////////////////////
        label = new JLabel("Send frame #        ", JLabel.CENTER);

        getContentPane().add(LabelPanel, BorderLayout.CENTER);
        getContentPane().setSize(new Dimension(600, 370));
        getContentPane().setVisible(true);

        LabelPanel.setLayout(new GridLayout(1, 0));

        TF.setForeground(Color.BLACK);
        // Hintergrundfarbe wird gesetzt
        TF.setBackground(Color.WHITE);
        TF.setBounds(10, 10, 70, 20);

        LabelPanel.add(TF);
        LabelPanel.add(ilabel);
        LabelPanel.add(label, BorderLayout.CENTER);


        ilabel.setBounds(100, 30, 300, 400);

    }

    //------------------------------------
    //main
    //------------------------------------
    public static void main(String argv[]) throws Exception {
        //create a Server object
        Server theServer = new Server();

        //show GUI:
        theServer.pack();
        theServer.setVisible(true);

        //get RTSP socket port from the command line
        int RTSPport = Integer.parseInt(argv[0]);
        LostRate= Integer.parseInt(argv[1]);

        fec_size=Integer.parseInt(argv[2]);

        //Initiate TCP connection with the client for the RTSP session
        ServerSocket listenSocket = new ServerSocket(RTSPport);
        theServer.RTSPsocket = listenSocket.accept();
        listenSocket.close();

        //Get Client IP address
        theServer.ClientIPAddr = theServer.RTSPsocket.getInetAddress();

        System.out.println("Server Address:"+ theServer.ClientIPAddr);

        //Initiate RTSPstate
        state = INIT;

        //Set input and output stream filters:
        RTSPBufferedReader = new BufferedReader(new InputStreamReader(theServer.RTSPsocket.getInputStream()));
        RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theServer.RTSPsocket.getOutputStream()));

        //Wait for the SETUP message from the client
        int request_type;
        boolean done = false;
        while (!done) {
            request_type = theServer.parse_RTSP_request(); //blocking

            if (request_type == SETUP) {
                done = true;

                //update RTSP state
                state = READY;
                System.out.println("New RTSP state: READY");

                //Send response
                theServer.send_RTSP_response();

                //init the VideoStream object:
                theServer.video = new VideoStream(VideoFileName);

                //init RTP socket
                theServer.RTPsocket = new DatagramSocket();
            }
        }

        //loop to handle RTSP requests
        while (true) {
            //parse the request
            request_type = theServer.parse_RTSP_request(); //blocking

            if ((request_type == PLAY) && (state == READY)) {
                //send back response
                theServer.send_RTSP_response();
                //start timer
                theServer.timer.start();
                //update state
                state = PLAYING;
                System.out.println("New RTSP state: PLAYING");
            } else if ((request_type == PAUSE) && (state == PLAYING)) {
                //send back response
                theServer.send_RTSP_response();
                //stop timer
                theServer.timer.stop();
                //update state
                state = READY;
                System.out.println("New RTSP state: READY");
            } else if ((request_type == OPTIONS)) {
                //send back response
                theServer.send_RTSP_OPTIONS_response();
                System.out.println("Send OPTIONS");
            } else if (request_type == TEARDOWN) {
                //send back response
                theServer.send_RTSP_response();
                //stop timer
                theServer.timer.stop();
                //close sockets
                theServer.RTSPsocket.close();
                theServer.RTPsocket.close();

                System.exit(0);
            }
        }
    }

/////////////////////////////////////////////
// Randomparser
    public static int myRandomWithHigh(int low, int high) {
        high++;
        return (int) (Math.random() * (high - low) + low);
    }
////////////////////////////////////////////////////
    //------------------------
    //Handler for timer
    //------------------------

    public void actionPerformed(ActionEvent e) {


        if (imagenb < VIDEO_LENGTH) {
            imagenb++;


                try {
                    //get next frame to send from the video, as well as its size
                    int image_length = video.getnextframe(buf);

                    //Builds an RTPpacket object containing the frame
                    RTPpacket rtp_packet = new RTPpacket(MJPEG_TYPE, imagenb, imagenb * FRAME_PERIOD, buf, image_length);

                    //get to total length of the full rtp packets to send
                    int packet_length = rtp_packet.getlength();

                    //retrieve the packets bitstream and store it in an array of bytes
                    byte[] packet_bits = new byte[packet_length];
                    rtp_packet.getpacket(packet_bits);
                    if( Rand.nextInt(100) > (int)(LostRate) ) {
                        //send the packets as a DatagramPacket over the UDP socket
                        senddp = new DatagramPacket(packet_bits, packet_length, ClientIPAddr, RTP_dest_port);
                        RTPsocket.send(senddp);
                    }
                    //send fec packets

                    packets.add(rtp_packet);
                    //Create FEC Packets
                    //quick fix
                    if (fec_size == 0) fec_size=1;
                    if (imagenb % (fec_size) == 0) {

                        System.out.println("Group_Count:" + imagenb);

                        FECpacket fec = new FECpacket(packets);
                        int fec_length = fec.getlength();
                        byte[] fec_packet_bits = new byte[fec_length];
                        //fec_packet_bits = new byte[fec_length];
                        fec.getpacket(fec_packet_bits);

                        //send the packets as a DatagramPacket over the UDP socket

                        senddp = new DatagramPacket(fec_packet_bits, fec_length, ClientIPAddr, RTP_dest_port);
                        RTPsocket.send(senddp);
                        packets.clear();
                    }


                    //System.output.println("Send frame #"+imagenb);
                    //print the header bitstream
                    rtp_packet.printheader();

                    //update GUI
                    label.setText("Send frame #" + imagenb);




                } catch (Exception ex) {
                    System.out.println("Exception caught: " + ex);
                    System.exit(0);
                }

        }
        else {
            //if we have reached the end of the video file, stop the timer
            timer.stop();
        }
    }



    //------------------------------------
    //Parse RTSP Request
    //------------------------------------
    private int parse_RTSP_request() {
        int request_type = -1;

        try {
            //parse request line and extract the request_type:
            String RequestLine = RTSPBufferedReader.readLine();
            //System.output.println("RTSP Server - Received from Client:");
            System.out.println(RequestLine);

            StringTokenizer tokens = new StringTokenizer(RequestLine);
            String request_type_string = tokens.nextToken();

            //convert to request_type structure:
            if ((new String(request_type_string)).compareTo("SETUP") == 0) {
                request_type = SETUP;
            } else if ((new String(request_type_string)).compareTo("PLAY") == 0) {
                request_type = PLAY;
            } else if ((new String(request_type_string)).compareTo("PAUSE") == 0) {
                request_type = PAUSE;
            } else if ((new String(request_type_string)).compareTo("TEARDOWN") == 0) {
                request_type = TEARDOWN;/////////////////////////////////////////////////////////
            } else if ((new String(request_type_string)).compareTo("OPTIONS") == 0) {
                request_type = OPTIONS;
            }
////////////////////////////////////////////////////////////////////////
            if (request_type == SETUP) {
                //extract VideoFileName from RequestLine
                VideoFileName = tokens.nextToken();
            }

            //parse the SeqNumLine and extract CSeq field
            String SeqNumLine = RTSPBufferedReader.readLine();
            System.out.println(SeqNumLine);
            tokens = new StringTokenizer(SeqNumLine);
            tokens.nextToken();
            RTSPSeqNb = Integer.parseInt(tokens.nextToken());

            //get LastLine
            String LastLine = RTSPBufferedReader.readLine();
            System.out.println(LastLine);

            if (request_type == SETUP) {
                //extract RTP_dest_port from LastLine
                tokens = new StringTokenizer(LastLine);
                for (int i = 0; i < 3; i++) {
                    tokens.nextToken(); //skip unused stuff
                }
                RTP_dest_port = Integer.parseInt(tokens.nextToken());
            }

            //////////////////////////////////////////////////////////
            if (request_type == OPTIONS) {
                //extract RTP_dest_port from LastLine
                tokens = new StringTokenizer(LastLine);

                tokens.nextToken(); //skip unused stuff
                requestedFeature = tokens.nextToken();
                requestedFeatureParameter = RTSPBufferedReader.readLine();
                //RTSPBufferedReader.
            }

            //////////////////////////////////////////////////////
            //else LastLine will be the SessionId line ... do not check for now.
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }
        return (request_type);
    }

    //------------------------------------
    //Send RTSP Response
    //------------------------------------
    private void send_RTSP_response() {
        try {
            RTSPBufferedWriter.write("RTSP/1.0 200 OK" + CRLF);
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);
            RTSPBufferedWriter.write("Session: " + RTSP_ID + CRLF);
            RTSPBufferedWriter.flush();
            //System.output.println("RTSP Server - Sent response to Client.");
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }
    }

    private void send_RTSP_OPTIONS_response() {
        //this is a list of available features
        String features = "implicit-play hello-world dresden";
        try {
            RTSPBufferedWriter.write("RTSP/1.0 200 OK" + CRLF);
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);

            if (features.contains(requestedFeature)) {
                RTSPBufferedWriter.write("Public: SETUP, PLAY, PAUSE, TEARDOWN" + CRLF);
            } else {
                RTSPBufferedWriter.write("Unsupported: " + requestedFeature + CRLF);
            }

            RTSPBufferedWriter.flush();
            //System.output.println("RTSP Server - Sent response to Client.");
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }
    }
}
