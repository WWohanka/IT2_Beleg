#!/bin/bash

# Server [Port] [Loss Rate in percent] [k value for FECPacket]


echo [Server] Geben Sie einen Port an:
read port
echo [Server] Verlustrate in Prozent:
read loss_rate
echo [Server] Parameter k:
read k
echo Starte Server...

java Server $port $loss_rate $k


# Client [Serveraddress] [Port] [Filename of Media]

echo [Client] Geben Sie die Adresse des Servers an:
read server_address
echo [Client] Dateiname der Mediendatei:
read media
echo Starte Client...

java Client server_address $port $media

