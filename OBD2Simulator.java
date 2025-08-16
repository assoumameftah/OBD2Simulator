package org.example;

import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class OBD2Simulator {
    private static final byte FRAME_DELIMITER = (byte) 0xF8;
    private static final byte ESCAPE_BYTE = (byte) 0xF7;
    private static final byte GPS_DATA = (byte) 0x01;
    private static final byte LBS_DATA = (byte) 0x02;
    private static final byte STT_DATA = (byte) 0x03;
    private static final byte MGR_DATA = (byte) 0x04;
    private static final byte OBD_DATA = (byte) 0x07;

    private String deviceId;
    private Socket socket;
    private DataOutputStream outputStream;
    private int frameCounter = 0;
    private boolean useGPS = true;
    private boolean is3DGPS = false;

    public OBD2Simulator(String deviceId) {
        this.deviceId = deviceId;
    }

    public boolean connectToServer(String serverHost, int serverPort) {
        try {
            socket = new Socket(serverHost, serverPort);
            outputStream = new DataOutputStream(socket.getOutputStream());
            System.out.println("Connecté au serveur " + serverHost + ":" + serverPort);
            return true;
        } catch (IOException e) {
            System.err.println("Erreur de connexion: " + e.getMessage());
            return false;
        }
    }

    public byte[] generateBinaryFrame() {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        try {
            frameCounter++;
            byte protocolVersion = (frameCounter % 6 == 0) ? (byte) 0x02 : (byte) 0x01;
            frame.write(protocolVersion);
            frame.write(0x01);
            byte[] deviceIdBytes = deviceId.getBytes();
            byte[] paddedDeviceId = new byte[15];
            System.arraycopy(deviceIdBytes, 0, paddedDeviceId, 0, Math.min(deviceIdBytes.length, 15));
            frame.write(paddedDeviceId);

            long secondsSince2000 = Duration.between(
                    LocalDateTime.of(2000, 1, 1, 0, 0, 0),
                    LocalDateTime.now()
            ).getSeconds();

            if (useGPS) {
                is3DGPS = !is3DGPS;
                if (is3DGPS) {
                    secondsSince2000 |= 0x80000000L;
                }
            }

            frame.write((int) (secondsSince2000 >> 24) & 0xFF);
            frame.write((int) (secondsSince2000 >> 16) & 0xFF);
            frame.write((int) (secondsSince2000 >> 8) & 0xFF);
            frame.write((int) secondsSince2000 & 0xFF);

            if (useGPS) {
                addGPSData(frame);
                System.out.println("Trame avec données GPS " + (is3DGPS ? "3D" : "2D"));
            } else {
                addLBSData(frame);
                System.out.println("Trame avec données LBS");
            }
            useGPS = !useGPS;

            addOBDData(frame);
            addStatusData(frame);
            addMileageData(frame);

            byte[] frameDataForCRC = frame.toByteArray();
            int crc = calculateCRC16(frameDataForCRC);
            frame.write((crc >> 8) & 0xFF);
            frame.write(crc & 0xFF);

            System.out.println("Version protocole: 0." + protocolVersion + " (Trame #" + frameCounter + ")");
            return frame.toByteArray();

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void addGPSData(ByteArrayOutputStream frame) throws IOException {
        frame.write(GPS_DATA);
        double latitude = 36.8065 + ThreadLocalRandom.current().nextDouble(-0.01, 0.01);
        double longitude = 10.1815 + ThreadLocalRandom.current().nextDouble(-0.01, 0.01);
        int speed = ThreadLocalRandom.current().nextInt(0, 120);
        int direction = ThreadLocalRandom.current().nextInt(0, 360);
        int hdop = ThreadLocalRandom.current().nextInt(5, 20);

        ByteArrayOutputStream gpsData = new ByteArrayOutputStream();
        int latInt = (int) (latitude * 1000000);
        gpsData.write((latInt >> 24) & 0xFF);
        gpsData.write((latInt >> 16) & 0xFF);
        gpsData.write((latInt >> 8) & 0xFF);
        gpsData.write(latInt & 0xFF);
        int lonInt = (int) (longitude * 1000000);
        gpsData.write((lonInt >> 24) & 0xFF);
        gpsData.write((lonInt >> 16) & 0xFF);
        gpsData.write((lonInt >> 8) & 0xFF);
        gpsData.write(lonInt & 0xFF);
        gpsData.write((speed >> 8) & 0xFF);
        gpsData.write(speed & 0xFF);
        gpsData.write((direction >> 8) & 0xFF);
        gpsData.write(direction & 0xFF);
        gpsData.write(hdop & 0xFF);

        if (is3DGPS) {
            int altitude = ThreadLocalRandom.current().nextInt(0, 1000);
            gpsData.write((altitude >> 8) & 0xFF);
            gpsData.write(altitude & 0xFF);
        }

        byte[] gpsBytes = gpsData.toByteArray();
        frame.write(gpsBytes.length);
        frame.write(gpsBytes);
    }

    private void addLBSData(ByteArrayOutputStream frame) throws IOException {
        frame.write(LBS_DATA);
        ByteArrayOutputStream lbsData = new ByteArrayOutputStream();
        int mcc = 605;
        int mnc = ThreadLocalRandom.current().nextInt(1, 4);
        int lac = ThreadLocalRandom.current().nextInt(1000, 9999);
        int cellId = ThreadLocalRandom.current().nextInt(10000, 99999);
        int signalStrength = ThreadLocalRandom.current().nextInt(-100, -50);

        lbsData.write((mcc >> 8) & 0xFF);
        lbsData.write(mcc & 0xFF);
        lbsData.write((mnc >> 8) & 0xFF);
        lbsData.write(mnc & 0xFF);
        lbsData.write((lac >> 8) & 0xFF);
        lbsData.write(lac & 0xFF);
        lbsData.write((cellId >> 24) & 0xFF);
        lbsData.write((cellId >> 16) & 0xFF);
        lbsData.write((cellId >> 8) & 0xFF);
        lbsData.write(cellId & 0xFF);
        lbsData.write(Math.abs(signalStrength) & 0xFF);

        byte[] lbsBytes = lbsData.toByteArray();
        frame.write(lbsBytes.length);
        frame.write(lbsBytes);
    }

    private void addOBDData(ByteArrayOutputStream frame) throws IOException {
        frame.write(OBD_DATA);
        ByteArrayOutputStream obdData = new ByteArrayOutputStream();

        obdData.write(0x0C);
        int rpm = ThreadLocalRandom.current().nextInt(800, 4000);
        obdData.write((rpm >> 8) & 0xFF);
        obdData.write(rpm & 0xFF);

        obdData.write(0x0D);
        int vehicleSpeed = ThreadLocalRandom.current().nextInt(0, 120);
        obdData.write(vehicleSpeed & 0xFF);

        obdData.write(0x05);
        int coolantTemp = ThreadLocalRandom.current().nextInt(80, 105);
        obdData.write(coolantTemp & 0xFF);

        obdData.write(0x0F);
        int intakeTemp = ThreadLocalRandom.current().nextInt(20, 60);
        obdData.write(intakeTemp & 0xFF);

        obdData.write(0x11);
        int throttlePos = ThreadLocalRandom.current().nextInt(0, 100);
        obdData.write(throttlePos & 0xFF);

        byte[] obdBytes = obdData.toByteArray();
        frame.write(obdBytes.length);
        frame.write(obdBytes);
    }

    private void addStatusData(ByteArrayOutputStream frame) throws IOException {
        frame.write(STT_DATA);
        int status = 0;
        status |= ThreadLocalRandom.current().nextBoolean() ? 0x01 : 0;
        status |= ThreadLocalRandom.current().nextBoolean() ? 0x02 : 0;
        status |= ThreadLocalRandom.current().nextBoolean() ? 0x04 : 0;
        status |= ThreadLocalRandom.current().nextBoolean() ? 0x08 : 0;

        frame.write(2);
        frame.write((status >> 8) & 0xFF);
        frame.write(status & 0xFF);
    }

    private void addMileageData(ByteArrayOutputStream frame) throws IOException {
        frame.write(MGR_DATA);
        int mileageMeters = ThreadLocalRandom.current().nextInt(50000000, 200000000);

        frame.write(4);
        frame.write((mileageMeters >> 24) & 0xFF);
        frame.write((mileageMeters >> 16) & 0xFF);
        frame.write((mileageMeters >> 8) & 0xFF);
        frame.write(mileageMeters & 0xFF);
    }

    public byte[] escapeBytes(byte[] data) {
        ByteArrayOutputStream escaped = new ByteArrayOutputStream();
        for (byte b : data) {
            if (b == FRAME_DELIMITER || b == ESCAPE_BYTE) {
                escaped.write(ESCAPE_BYTE);
                escaped.write(b ^ ESCAPE_BYTE);
            } else {
                escaped.write(b);
            }
        }
        return escaped.toByteArray();
    }


    public byte[] encapsulateFrame(byte[] frameData) {
        byte[] escapedData = escapeBytes(frameData);
        ByteArrayOutputStream finalFrame = new ByteArrayOutputStream();
        finalFrame.write(FRAME_DELIMITER);
        try {
            finalFrame.write(escapedData);
        } catch (IOException e) {
            e.printStackTrace();
        }
        finalFrame.write(FRAME_DELIMITER);
        return finalFrame.toByteArray();
    }

    private int calculateCRC16(byte[] data) {
        int crc = 0x0000;
        int polynomial = 0x1021;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ polynomial;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    public boolean sendFrame() {
        try {
            byte[] frameData = generateBinaryFrame();
            if (frameData == null) {
                System.err.println("Erreur lors de la génération de la trame");
                return false;
            }
            byte[] finalFrame = encapsulateFrame(frameData);
            outputStream.write(finalFrame);
            outputStream.flush();
            System.out.println("Trame envoyée (" + finalFrame.length + " octets)");
            System.out.println("Données hex: " + bytesToHex(finalFrame));
            return true;
        } catch (IOException e) {
            System.err.println("Erreur lors de l'envoi: " + e.getMessage());
            return false;
        }
    }

    public void startSimulation(int intervalSeconds) {
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!sendFrame()) {
                    System.err.println("Échec de l'envoi, arrêt de la simulation");
                    timer.cancel();
                }
            }
        }, 0, intervalSeconds * 1000);
        System.out.println("Simulation démarrée, envoi toutes les " + intervalSeconds + " secondes");
    }

    public void disconnect() {
        try {
            if (outputStream != null) outputStream.close();
            if (socket != null) socket.close();
            System.out.println("Connexion fermée");
        } catch (IOException e) {
            System.err.println("Erreur lors de la fermeture: " + e.getMessage());
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        String deviceId = "357852034572894";
        String serverHost = "localhost";
        int serverPort = 8080;
        OBD2Simulator simulator = new OBD2Simulator(deviceId);

        if (simulator.connectToServer(serverHost, serverPort)) {
            simulator.startSimulation(5);
        }
    }
}
