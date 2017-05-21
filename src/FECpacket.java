import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Created by wolfgang_wohanka on 07/01/17.
 */
public class FECpacket {

    //size of FEC Header

    public static final int FEC_HDR_LEN = 14;
    public static final int RTP_HDR_LEN = 12;

    public byte[] Header;

    //Fields that compose the RTP header
    public int Version;
    public int Padding;
    public int Extension;
    public int CC;
    public int Marker;
    public int PayloadType = 127;
    public int SequenceNumber;
    public int TimeStamp;
    public int Ssrc;
    public int Long_Mask;

    // Forward error correction
    public int PaddingRecovery;
    public int ExtensionRecovery;
    public int CCRecovery;
    public int MarkerRecovery;


    public int protectionLength;

    public int       sequence;

    public int       snbase;              // 24 bits
    public byte      offset;              //  8 bits
    public int      na;                  //  8 bits
    public byte      payloadTypeRecovery; //  7 bits
    public int       timestampRecovery;   // 32 bits
    public int       lengthRecovery;      // 16 bits
    public byte[]    payloadRecovery;


    public byte    index;    //  3 bits
    public int     mask;     // 24 bits
    public boolean extended; //  1 bit
    public boolean n;        //  1 bit

    protected int      missingCount;


    public FECpacket() {}

    // Parse a byte sequence to a FEC packet
    public FECpacket(byte[] pBytes, int pLength) {
        this(new RTPpacket(pBytes, pLength));
    }

    // Convert a RTP packet to a FEC packet
    public FECpacket(RTPpacket pPacket) {

        sequence   = pPacket.SequenceNumber;

        snbase         = get16bits(pPacket.payload, 2);

        timestampRecovery = get32bits(pPacket.payload, 4);

        lengthRecovery = get16bits(pPacket.payload, 8);

        protectionLength  = get16bits(pPacket.payload, 10);

        mask = get16bits(pPacket.payload, 12);

        na = countOne(mask);


        payloadRecovery = // And finally ... The payload !
                Arrays.copyOfRange(pPacket.payload, FEC_HDR_LEN, pPacket.payload.length);
    }


    // Create a new FEC packet based on RTP packets payload (xor'ed)
    public FECpacket(List<RTPpacket> pPackets) {

        if (pPackets == null) throw new IllegalArgumentException("pPackets is null");

        Version = 2;
        Padding = 0;
        Extension = 0;
        CC = 0;
        Marker = 0;
        Ssrc = 0;
        Long_Mask = 0;

        Header = new byte[RTP_HDR_LEN + FEC_HDR_LEN];

        //RTP Header:
        /*
        0                   1                   2                   3
        0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        |V=2|P|X|  CC   |M|     PT      |       sequence number         |
        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        |                           timestamp                           |
        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        |           synchronization source (SSRC) identifier            |
        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
        |            contributing source (CSRC) identifiers             |
        |                             ....                              |
        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        */

        Header[0] = (byte) (Version << 6);
        Header[0] = (byte) (Header[0] | Padding << 5);
        Header[0] = (byte) (Header[0] | Extension << 4);
        Header[0] = (byte) (Header[0] | CC);

        Header[1] = (byte) (Header[1] | Marker << 7);
        Header[1] = (byte) (Header[1] | PayloadType);

        Header[2] = (byte) (SequenceNumber >> 8);
        Header[3] = (byte) (SequenceNumber & 0xFF);

        Header[4] = (byte) (TimeStamp >> 24);
        Header[5] = (byte) (TimeStamp >> 16);
        Header[6] = (byte) (TimeStamp >> 8);
        Header[7] = (byte) (TimeStamp & 0xFF);

        Header[8] = (byte) (Ssrc >> 24);
        Header[9] = (byte) (Ssrc >> 16);
        Header[10] = (byte) (Ssrc >> 8);
        Header[11] = (byte) (Ssrc & 0xFF);



        Collections.sort(pPackets);

        snbase = pPackets.get(0).SequenceNumber;

        // Detect maximum length of packets payload and check packets validity
        int size = 0;
        for (int i = 0; i < pPackets.size(); i++) {
            RTPpacket packet = pPackets.get(i);
            size = Math.max(size, packet.getpayload_length());
        }


        // Create payload fail_recovery field according to size/length
        payloadRecovery = new byte[size];
        // Compute fec packet's fields based on input packets
        for (int i = 0; i < pPackets.size(); i++) {
            // Update (...) fail_recovery fields by xor'ing corresponding fields of all packets
            RTPpacket packet = pPackets.get(i);

            //XOR 1st Byte
            PaddingRecovery ^= packet.Padding;
            ExtensionRecovery ^= packet.Extension;
            CCRecovery ^= packet.CC;

            //XOR 2nd Byte
            MarkerRecovery ^= packet.Marker;
            payloadTypeRecovery ^= packet.PayloadType;

            //XOR Byte 5,6,7,8,9,10
            timestampRecovery ^= packet.TimeStamp;
            lengthRecovery ^= packet.payload_size;


            // Update payload fail_recovery by xor'ing all packets payload
            for (int no = 0; no < Math.min(size, packet.payload.length); no++) {
                payloadRecovery[no] ^= packet.payload[no];
            }

        }

        //FEC Header

        /*
            0                   1                   2                   3
            0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |E|L|P|X|  CC   |M| PT fail_recovery |            SN base            |
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |                          TS fail_recovery                          |
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            |        length fail_recovery        |
            +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         */


        Header[12] = (byte) (Extension << 7);
        Header[12] = (byte) (Header[12] | Long_Mask << 6);
        Header[12] = (byte) (Header[12] | PaddingRecovery << 5);
        Header[12] = (byte) (Header[12] | ExtensionRecovery << 4);
        Header[12] = (byte) (Header[12] | CCRecovery);

        Header[13] = (byte) (Header[13] | MarkerRecovery << 7);
        Header[13] = (byte) (Header[13] | payloadTypeRecovery);

        Header[14] = (byte)(snbase >> 8);
        Header[15] = (byte)(snbase & 0xff);

        Header[16] = (byte)((timestampRecovery >> 24) & 0xFF);
        Header[17] = (byte)((timestampRecovery >> 16) & 0xFF);
        Header[18] = (byte)((timestampRecovery >> 8) & 0xFF);
        Header[19] = (byte)(timestampRecovery & 0xFF);

        Header[20] = (byte)((lengthRecovery  >> 8) & 0xFF);
        Header[21] = (byte)(lengthRecovery  & 0xff);

        /*
          4 byte FEC Level 0 Header (the short mask is always used):
          0                   1                   2                   3
          0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
          +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
          |       Protection Length       |             mask              |
          +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         */
        protectionLength=size;

        Header[22] = (byte) (protectionLength>>8 & 0xff);
        Header[23] = (byte) (protectionLength & 0xff);



        // assume all packets were added
        mask = ((1<<pPackets.size()) - 1) << (16-pPackets.size());

        Header[24] = (byte) (mask>>8 & 0xff);
        Header[25] = (byte) (mask & 0xff);

    }

    public int getPayloadSize()
    {
        return payloadRecovery.length;
    }


    public int setMissing(int pMediaSequence)
    {
        int j = computeJ(pMediaSequence);

        missingCount++;

        return j;
    }
    protected int computeJ(int pMediaSequence)
    {
        int delta = pMediaSequence - snbase;
        if (delta < 0) delta = RTPpacket.S_MASK+1 + delta;

        return delta;
    }


    public int getpacket(byte[] packet)
    {
        //construct the packet = header + payload
        for (int i=0; i < RTP_HDR_LEN + FEC_HDR_LEN; i++)
            packet[i] = Header[i];
        for (int i=0; i < payloadRecovery.length; i++)
            packet[i+RTP_HDR_LEN + FEC_HDR_LEN] = payloadRecovery[i];

        //return total size of the packet
        return(this.getPayloadSize() + RTP_HDR_LEN + FEC_HDR_LEN);
    }

    //--------------------------
    //getlength: return the total length of the FEC packet
    //--------------------------
    public int getlength() {
        return (this.getPayloadSize()  + RTP_HDR_LEN + FEC_HDR_LEN);
    }

    public static int get16bits(byte[] raw, int offset)
    {
        return (int)((raw[offset] & 0xff) << 8) + (int)(raw[offset+1] & 0xff);
    }

    public static int get24bits(byte[] raw, int offset)
    {
        return (int)((raw[offset]   & 0xff) << 24) + (int)((raw[offset+1] & 0xff) << 16) +
                (int)((raw[offset+2] & 0xff) <<  8);
    }
    public static int get32bits(byte[] raw, int offset)
    {
        return (int)((raw[offset]   & 0xffL) << 24) + (int)((raw[offset+1] & 0xffL) << 16) +
                (int)((raw[offset+2] & 0xffL) <<  8) + (int) (raw[offset+3] & 0xffL);
    }


    public static int countOne(int number){
        int count = 0;
        for(int i =0; i<32; i++){
            if( (number&1) == 1) {
                count++;
            }
            number = number >>> 1;
        }
        return count;
    }



}
