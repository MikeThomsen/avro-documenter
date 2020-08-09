package org.apache.avro.documentation;

import j2html.tags.ContainerTag;
import j2html.tags.DomContent;
import org.apache.avro.Schema;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static j2html.TagCreator.*;

public class App {
    private static CommandLine parseArguments(String[] args) throws ParseException {
        Options options = new Options();

        Option input = new Option("s", "schema", true, "Avro schema file");
        input.setRequired(true);
        options.addOption(input);

        Option output = new Option("o", "output", true, "Output file to store the ES schema.");
        output.setRequired(true);
        options.addOption(output);

        CommandLineParser parser = new DefaultParser();

        return parser.parse(options, args);
    }

    public static Optional<List<Schema>> checkForRecord(Schema fieldSchema) {
        Schema.Type type = fieldSchema.getType();
        if (type == Schema.Type.RECORD) {
            return Optional.of(Arrays.asList(fieldSchema));
        } else if (type == Schema.Type.ARRAY && fieldSchema.getElementType().getType() == Schema.Type.RECORD) {
            return Optional.of(Arrays.asList(fieldSchema.getElementType()));
        } else if (type == Schema.Type.MAP && fieldSchema.getValueType().getType() == Schema.Type.RECORD) {
            return Optional.of(Arrays.asList(fieldSchema.getValueType()));
        } else if (type == Schema.Type.UNION) {
            List<Schema> unionTypes = fieldSchema.getTypes();
            List<Schema> retVal = new ArrayList<>();
            unionTypes
                .stream()
                .map(App::checkForRecord)
                .forEach(result -> {
                    if (result.isPresent()) {
                        retVal.addAll(result.get());
                    }
                });

            return retVal.size() > 0 ? Optional.of(retVal) : Optional.empty();
        } else {
            return Optional.empty();
        }
    }

    public static void getReferencedRecords(final Schema schema, final List<Schema> referencedRecords) {
        schema.getFields().forEach(field -> {
            Optional<List<Schema>> result = checkForRecord(field.schema());
            if (result.isPresent()) {
                List<Schema> extracted = result.get();
                extracted.forEach(extractedSchema -> {
                    if (!referencedRecords.contains(extractedSchema)) {
                        referencedRecords.add(extractedSchema);
                    }

                    getReferencedRecords(extractedSchema, referencedRecords);
                });
            }
        });
    }

    public static ContainerTag convertRecordToHtml(Schema record) {
        return div(
            h3(record.getFullName()),
            table(
                tbody(
                    tr(
                        th("Field Name"),
                        th("Field Type"),
                        th("Documentation")
                    ),
                    each(record.getFields(), field -> tr(
                        td(field.name()),
                        td(field.schema().getFullName()),
                        td(field.doc() != null ? field.doc() : "None provided.")
                    ))
                )
            ),
            br(),
            br()
        );
    }

    public static ContainerTag createBody(ContainerTag... tags) {
        return html(
            body(
                tags
            )
        );
    }

    public static void main(String[] args) throws Exception {
        CommandLine arguments = parseArguments(args);
        String schema = arguments.getOptionValue("schema");
        String output = arguments.getOptionValue("output");

        File rawInput = new File(schema);
        if (!rawInput.exists()) {
            System.out.println(String.format("%s does not exist.", schema));
            System.exit(1);
        }

        Schema parsed = new Schema.Parser().parse(new FileInputStream(rawInput));
        List<Schema> referencedRecords = new ArrayList<>();
        getReferencedRecords(parsed, referencedRecords);

        System.out.println(String.format("Extracted %d record schemas.", referencedRecords.size()));

        List<ContainerTag> convertedRecords = referencedRecords
                .stream()
                .map(record -> convertRecordToHtml(record))
                .collect(Collectors.toList());
        String body = createBody(convertedRecords.toArray(
                new ContainerTag[convertedRecords.size()]
        )).renderFormatted();

        FileWriter writer = new FileWriter(String.format("%s.html", output));
        writer.write(body);
        writer.close();
    }
}
