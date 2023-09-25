package datawave.util.flag;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;

import com.google.common.annotations.VisibleForTesting;

import datawave.util.flag.config.FlagDataTypeConfig;
import datawave.util.flag.config.FlagMakerConfig;

public class FlagFileContentCreator {
    private static final char NEWLINE = '\n';
    private static final char SPACE = ' ';
    private static final char COMMA = ',';

    // See execute-ingest.sh. It prepares a command from the flag file,
    // replacing
    // the ${JOB_FILE} variable with the *-ingest-server.sh provided flag file
    // name (after stripping .inprogress)
    private static final String PLACEHOLDER_VARIABLE = "${JOB_FILE}";

    private final FlagMakerConfig flagMakerConfig;

    public FlagFileContentCreator(FlagMakerConfig flagMakerConfig) {
        this.flagMakerConfig = flagMakerConfig;
    }

    void writeFlagFileContents(FileOutputStream flagOutputStream, Collection<InputFile> inputFiles, FlagDataTypeConfig fc) throws IOException {
        String content = createContent(inputFiles, fc);
        flagOutputStream.write(content.getBytes());
    }

    int calculateSize(Collection<InputFile> inputFiles, FlagDataTypeConfig fc) {
        return createContent(inputFiles, fc).length();
    }

    @VisibleForTesting
    String createContent(Collection<InputFile> inputFiles, FlagDataTypeConfig fc) {
        StringBuilder sb = new StringBuilder(flagMakerConfig.getDatawaveHome() + File.separator + fc.getScript());

        if (fc.getFileListMarker() == null) {
            char sep = SPACE;
            for (InputFile inFile : inputFiles) {
                sb.append(sep);
                sb.append(inFile.getFlagged().toUri());
                sep = COMMA;
            }
        } else {
            sb.append(" ");
            // add a placeholder variable which will later resolve to the flag
            // file .inprogress. The baseName could change by then.
            sb.append(PLACEHOLDER_VARIABLE);
        }

        sb.append(SPACE).append(fc.getReducers()).append(" -inputFormat ").append(fc.getInputFormat().getName()).append(SPACE);

        if (fc.getFileListMarker() != null) {
            sb.append("-inputFileLists -inputFileListMarker ");
            sb.append(fc.getFileListMarker());
            sb.append(SPACE);
        }
        if (fc.getExtraIngestArgs() != null) {
            sb.append(fc.getExtraIngestArgs());
        }

        sb.append(NEWLINE);

        if (fc.getFileListMarker() != null) {
            sb.append(fc.getFileListMarker()).append(NEWLINE);
            for (InputFile inFile : inputFiles) {
                sb.append(inFile.getFlagged().toUri()).append(NEWLINE);
            }
        }
        return sb.toString();
    }
}
