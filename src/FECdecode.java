import java.io.IOException;
import java.util.*;

/**
 * Created by wolfgang_wohanka on 11/01/17.
 */
public class FECdecode {

    // Statistics
    protected int received;
    protected int recovered;
    // Failed recovery counter
    protected int fail_recovery;
    //overwritten Packets
    protected int overwritten;
    //missing Packets
    protected int missing;
    protected int maxMedia;

    //Packet storage
    protected TreeMap<Integer, RTPpacket> Packet_buffer;

    //Packet receiving has begun
    protected boolean startup;
    //position (sequence number) in the Packet_buffer buffer
    protected int     position;
    //Buffersize for incoming Packages
    protected int buffer = 20;

    // Output
    protected List<RTPpacket> rtpPackets_out;



    //Keeping track of lost Packets
    protected TreeMap<Integer, Integer> lost = new TreeMap<>();
    protected int lost_count = 0;

    public FECdecode(List<RTPpacket> Packets_out)
    {

        Packet_buffer = new TreeMap<>();

        startup    = true;

        rtpPackets_out = Packets_out;
    }

    // receive RTP packets
    public void rcvdata(RTPpacket pPacket)
    {
        // Put the media packet into Packet_buffer buffer
        if (Packet_buffer.put(pPacket.SequenceNumber, pPacket) != null) overwritten++;
        if (Packet_buffer.size() > maxMedia) maxMedia = Packet_buffer.size();

        received++;

        try {
            output();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public List<RTPpacket> getRecoveredPackages()
    {
        return rtpPackets_out;
    }


    //Receive an incoming FEC Packet and look for missing RTP Packets
    public void rcvfec(FECpacket pPacket)
    {

        FECpacket  wait  = pPacket;

        //keeping track of lost packages
        int mediaLost  = 0;
        int j = 0;
        int mediaMin   = wait.snbase;
        int mediaMax   = (wait.snbase + wait.na) & RTPpacket.S_MASK;
        for (int mediaTest = mediaMin; mediaTest != mediaMax; mediaTest = (mediaTest + 1) & RTPpacket.S_MASK)
        {
            // If media packet is not in the Packet_buffer buffer (is missing)
            if (!Packet_buffer.containsKey(mediaTest))
            {
                mediaLost = mediaTest;
                wait.setMissing(mediaTest);
                // Updates the "waiting" fec packet

            }
        }


        // No Packet is missing
        if (wait.missingCount == 0) return;


        // 1 Packet is missing -> Able to recover
        if (wait.missingCount == 1)
        {
            recoverMediaPacket(mediaLost, wait);
            try {
                output();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


    }


    protected void recoverMediaPacket(int rtp_sequencenr, FECpacket wait_fec)
    {
        boolean recoveredByFec = wait_fec != null;

        // Recover the missing media packet
        if (recoveredByFec)
        {
            RTPpacket media = new RTPpacket();

            // Copy FEC Packet into RTP Packet
            media.SequenceNumber    = rtp_sequencenr;
            media.PayloadType = wait_fec.payloadTypeRecovery;
            media.TimeStamp   = wait_fec.timestampRecovery;
            media.payload_size   = wait_fec.lengthRecovery;
            byte[] payload    = wait_fec.payloadRecovery;


            // XOR Recovered Packet with other RTPPackets from Buffer
            boolean aborted = false;
            int mediaMin =  wait_fec.snbase;
            int mediaMax = (wait_fec.snbase + wait_fec.na) & RTPpacket.S_MASK;
            for (int mediaTest = mediaMin; mediaTest != mediaMax; mediaTest = (mediaTest + 1) & RTPpacket.S_MASK)
            {
                if (mediaTest == rtp_sequencenr) continue;

                RTPpacket friend = Packet_buffer.get(mediaTest);

                // Unable to recover the media packet if any of the friend media packets is missing
                if (friend == null)
                {
                    fail_recovery++;
                    aborted = true;
                    break;
                }

                media.PayloadType ^= friend.PayloadType;
                media.TimeStamp   ^= friend.TimeStamp;
                media.payload_size ^= friend.getpayload_length();
                for (int no = 0; no < Math.min(payload.length, friend.payload.length); no++)
                {
                    payload[no] ^= friend.payload[no];
                }
            }

            // If the RTPpacket is successfully recovered

            if (!aborted)
            {
                media.payload = Arrays.copyOfRange(payload, 0, media.payload_size);

                recovered++;
                if (Packet_buffer.put(media.SequenceNumber, media) != null) overwritten++;
                if (Packet_buffer.size() > maxMedia) maxMedia = Packet_buffer.size();
            }
        }

    }

    protected void output() throws IOException {

        while (Packet_buffer.size() > buffer)
        {
            // Initialize or increment actual position (expected sequence number)
            position = (startup ? Packet_buffer.firstKey() : position+1) & RTPpacket.S_MASK;
            startup  = false;

            RTPpacket media = Packet_buffer.get(position);
            if (media != null)
            {
                // Update the histogram of the lost packets and reset the counter
                if (lost.containsKey(lost_count))
                    lost.put(lost_count, lost.get(lost_count) + 1);
                else
                    lost.put(lost_count, 1);
                lost_count = 0;

                // Remove the media of the buffer and output it
                Packet_buffer.remove(media.SequenceNumber);
                if (rtpPackets_out != null) rtpPackets_out.add(media);
            }
            else
            {
                // Increment the counters because another packet is missing ...
                missing++;
                lost_count++;
            }


        }

    }


    // Statistics Getter
    public int getReceived()        { return received;        }
    public int getMediaPosition()        { return position;             }
    public int getMediaBuffered()        { return Packet_buffer.size();        }
    public int getRecovered()       { return recovered;       }
    public int getFail_recovery() { return fail_recovery; }
    public int getOverwritten()     { return overwritten;     }
    public int getMissing()         { return missing;         }
    public int getMaxMediaBuffered()     { return maxMedia;             }

}
