package org.wikivoyage.listings;

import org.wikivoyage.listings.entity.WikivoyagePOI;
import org.wikivoyage.listings.input.*;
import org.wikivoyage.listings.language.Languages;
import org.wikivoyage.listings.output.*;
import org.wikivoyage.listings.utils.FileUtils;
import org.wikivoyage.listings.utils.FileUtilsException;

import java.io.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wikivoyage.listings.utils.UnrecognizeTemplateCounter;

public class Main {
    private static final Log log = LogFactory.getLog(Main.class);

    private static FileNames fileNames;

    public static void main(String[] args) {
        HashMap<String, OutputFormat> formats = new HashMap<>();
        formats.put("csv", new CSV());
        formats.put("osmand-xml", new OsmXml(false));
        formats.put("osmand-xml-user-defined", new OsmXml(true));
        formats.put("xml", new Xml());
        formats.put("obf", new OBF(false, "tmp", "tmp/pois.xml"));
        formats.put("obf-user-defined", new OBF(true, "tmp", "tmp/pois.xml"));
        formats.put("sql", new SQL());
        formats.put("gpx", new GPX());
        formats.put("osmand.gpx", new OsmAndGPX());
        formats.put("kml", new KML());
        formats.put("validation-report", new ValidationReport());


        CommandLine cl = new CommandLine();
        String [] formatNames = formats.keySet().toArray(new String [formats.keySet().size()]);
        cl.parse(args, formatNames);
        fileNames = new FileNames(cl.listingsDir, cl.dumpsCacheDir, cl.workingDir);

        try {
            if (cl.help) {
                cl.printHelp((String[])formats.keySet().toArray());
            } else if (cl.dailyUpdate) {
                dailyUpdate(cl, formats);
            } else {
                String inputFilename;
                String dumpDate = ""; // To embed in files to provide freshness information to users (example: "Generated from Wikivoyage 2016/07/20 data").
                createWorkingDir();
                if (cl.inputFile != null) {
                    inputFilename = cl.inputFile;
                    log.info("Take POIs from '" + inputFilename + "'");
                } else {
                    DumpDownloader downloader = new DumpDownloader();
                    if (cl.inputUrl != null) {
                        inputFilename = fileNames.workingDirPath("dump.xml.bz2");
                        downloader.downloadDumpFromUrl(cl.inputUrl, inputFilename);
                    } else {
                        createDumpsCacheDir();
                        List<String> dumpDates = downloader.listDumps(cl.inputLatest);
                        Collections.sort(dumpDates);
                        Collections.reverse(dumpDates);
                        dumpDate = dumpDates.get(0);

                        inputFilename = fileNames.dumpCacheFilename(cl.inputLatest, dumpDate);
                        if (!FileUtils.fileExists(inputFilename)) {
                            String dumpUrl = downloader.dumpUrl(cl.inputLatest, dumpDate);
                            downloader.downloadDumpFromUrl(dumpUrl, inputFilename);
                        } else {
                            log.info("Use cached dump");
                        }
                    }
                }

                if (cl.outputFormat != null) {
                    OutputFormat format = formats.get(cl.outputFormat);
                    generateFileForFormat(inputFilename, cl.outputFilename, format, dumpDate);
                }
                UnrecognizeTemplateCounter.getInstance().logUnrecognizeTemplatesSummary();
                log.info("Finished");
            }
        } catch (Exception e) {
            System.err.println("Failure");
            e.printStackTrace();
        }
    }

    private static void dailyUpdate(CommandLine cl, HashMap<String, OutputFormat> formats) throws IOException, FileUtilsException {
        createWorkingDir();
        createListingsDir();
        createDumpsCacheDir();

        DumpDownloader downloader = new DumpDownloader();
        for (String language: Languages.getLanguageCodes()) {
            log.info("Processing language " + language);
            List<String> dumpDates = downloader.listDumps(language);

            if (dumpDates.size() == 0) {
                continue;
            }

            Collections.sort(dumpDates);
            Collections.reverse(dumpDates);

            String latestDumpDate = dumpDates.get(0);

            if (cl.latestCount != null) {
                log.info("Processing the latest " + cl.latestCount + " dumps");
                dumpDates = dumpDates.subList(0, cl.latestCount);
            }

            for (String dumpDate: dumpDates) {
                log.info("Processing dump " + dumpDate);
                try {
                    processDump(downloader, language, latestDumpDate, dumpDate, formats, !cl.doNotUseIntermediateFile);
                } catch (Exception e) {
                    log.info("Failed to create dump " + dumpDate);
                    log.debug("Exception: ", e);
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    log.debug("Stack trace: " + sw.toString());
                }
            }
        }
        System.exit(0);
    }

    private static void processDump(
        DumpDownloader downloader, String language, String latestDumpDate, String dumpDate,
        HashMap<String, OutputFormat> formats, boolean useIntermediateFile
    ) throws IOException, FileUtilsException, InterruptedException, WriteOutputException {
        boolean allFileExists = true;
        for (OutputFormat format: formats.values()) {
            String fileName = fileNames.getListingPath(language, dumpDate, format.getDefaultExtension(), true);
            if (!FileUtils.fileExists(fileName)) {
                allFileExists = false;
                break;
            }
        }

        if (allFileExists) {
            log.info("All files already exist for '" + language + "-" + dumpDate + "'");
            return;
        }

        log.info("Create POIs for '" + dumpDate + "'");

        String dumpUrl = downloader.dumpUrl(language, dumpDate);
        String dumpPath = fileNames.dumpCacheFilename(language, dumpDate);
        if (!FileUtils.fileExists(dumpPath)) {
            downloader.downloadDumpFromUrl(dumpUrl, dumpPath);
        }

        Iterable<WikivoyagePOI> listingIterable = new DumpListingsIterable(dumpPath);

        if (useIntermediateFile) {
            log.info("Write intermediate file with parsed listings");
            String javaSerialFile = fileNames.workingDirPath("serialized-pois.bin");
            FileUtils.removeFile(javaSerialFile);
            new JavaSerializedObject().write(listingIterable, javaSerialFile, dumpDate);
            listingIterable = new JavaSerializedIterable(javaSerialFile);
        }


        for (OutputFormat format: formats.values()) {
            log.info(
                "Write output file for language " + language +
                ", dump " + dumpDate + ", format " + format.getDefaultExtension().substring(1)
            );
            String fileName = fileNames.getListingPath(language, dumpDate, format.getDefaultExtension(), false);
            try {
                format.write(listingIterable, fileName, dumpDate);
                if (dumpDate.equals(latestDumpDate)) {
                    String latestFileName = fileNames.getListingPath(
                            language, "latest", format.getDefaultExtension(), false
                    );
                    FileUtils.removeFile(latestFileName);
                    FileUtils.copyFile(fileName, latestFileName);
                }
                String fileNameArchive = fileNames.getListingPath(language, dumpDate, format.getDefaultExtension(), true);
                FileUtils.archive(fileName, fileNameArchive);
            } catch (WriteOutputException e) {
                System.out.println("Failed to write file: " + e.getMessage());
            }
        }
    }

    private static void createListingsDir() throws FileUtilsException
    {
        FileUtils.createDirectory(fileNames.getListingsDir());
    }

    private static void createDumpsCacheDir() throws FileUtilsException
    {
        FileUtils.createDirectory(fileNames.getDumpsCacheDir());
    }

    private static void createWorkingDir() throws FileUtilsException
    {
        FileUtils.createDirectory(fileNames.getWorkingDir());
    }

    private static void generateFileForFormat(
        String inputFilename, String outputFilename, OutputFormat format, String dumpDate
    ) throws WriteOutputException, DumpReadException {
        log.info("Parse dump");
        Iterable<WikivoyagePOI> listingIterable = new DumpListingsIterable(inputFilename);
        log.info("Save to '" + outputFilename + "'");
        format.write(listingIterable, outputFilename, dumpDate);
    }
}
