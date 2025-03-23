/*
 * Copyright 2013-2024, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.cli

import com.beust.jcommander.Parameter
import com.beust.jcommander.Parameters
import groovy.io.FileType
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.config.control.ConfigParser
import nextflow.config.formatter.ConfigFormattingVisitor
import nextflow.exception.AbortOperationException
import nextflow.script.control.ScriptParser
import nextflow.script.formatter.FormattingOptions
import nextflow.script.formatter.ScriptFormattingVisitor
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.messages.SyntaxErrorMessage

import org.fusesource.jansi.Ansi
import org.fusesource.jansi.AnsiConsole
import static nextflow.util.LoggerHelper.isHashLogPrefix
import static org.fusesource.jansi.Ansi.Attribute
import static org.fusesource.jansi.Ansi.Color
import static org.fusesource.jansi.Ansi.ansi
/**
 * CLI sub-command FORMAT
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 */
@Slf4j
@CompileStatic
@Parameters(commandDescription = "Format Nextflow scripts and config files")
class CmdFormat extends CmdBase {

    @Parameter(description = 'List of paths to format')
    List<String> args = []

    @Parameter(names=['-spaces'], description = 'Number of spaces to indent')
    int spaces

    @Parameter(names = ['-tabs'], description = 'Indent with tabs')
    Boolean tabs

    @Parameter(names = ['-harshil-alignment'], description = 'Use Harshil alignment')
    Boolean harhsilAlignment

    private ScriptParser scriptParser

    private ConfigParser configParser

    private FormattingOptions formattingOptions

    @Override
    String getName() { 'format' }

    @Override
    void run() {
        if( !args )
            throw new AbortOperationException("Error: No input files specified")

        if( spaces && tabs )
            throw new AbortOperationException("Error: Cannot specify both `-spaces` and `-tabs`")

        if( !spaces && !tabs )
            spaces = 4

        scriptParser = new ScriptParser()
        configParser = new ConfigParser()
        formattingOptions = new FormattingOptions(spaces, !tabs, harhsilAlignment, false)

        for( final arg : args ) {
            final file = new File(arg)
            if( file.isFile() ) {
                format(file)
                continue
            }

            file.eachFileRecurse(FileType.FILES, this.&format)
        }
    }

    void format(File file) {
        final name = file.getName()
        if( name.endsWith('.nf') )
            formatScript(file)
        else if( name.endsWith('.config') )
            formatConfig(file)
    }

    private void formatScript(File file) {
        final source = scriptParser.parse(file)
        if( source.getErrorCollector().hasErrors() ) {
            printErrors(source)
        }
        else {
            log.debug "Formatting script ${file}"
            printStatus(file)
            final formatter = new ScriptFormattingVisitor(source, formattingOptions)
            formatter.visit()
            // Check if anything changed)
            if(file.text != formatter.toString()){
                printHaveFormatted(file)
                file.text = formatter.toString()
            }
        }
    }

    private void formatConfig(File file) {
        final source = configParser.parse(file)
        if( source.getErrorCollector().hasErrors() ) {
            printErrors(source)
        }
        else {
            log.debug "Formatting config ${file}"
            printStatus(file)
            final formatter = new ConfigFormattingVisitor(source, formattingOptions)
            formatter.visit()
            // Check if anything changed)
            if(file.text != formatter.toString()){
                printHaveFormatted(file)
                file.text = formatter.toString()
            }
        }
    }

    private void printStatus(File file) {
        // TODO: Figure out how to chomp previous status
        final str = ansi().a(Attribute.INTENSITY_FAINT).a("Formatting: ${file}").reset().newline().toString()
        AnsiConsole.out.print(str)
        AnsiConsole.out.flush()
    }

    private void printHaveFormatted(File file) {
        final str = ansi().fg(Color.GREEN).a("Reformatted: ${file}").reset().newline().toString()
        AnsiConsole.out.print(str)
        AnsiConsole.out.flush()
    }

    private void printErrors(SourceUnit source) {
        final errorMessages = source.getErrorCollector().getErrors()
        final term = ansi()
        term.fg(Color.RED)
        for( final message : errorMessages ) {
            if( message instanceof SyntaxErrorMessage ) {
                final cause = message.getCause()
                term.a("Error: ${source.getName()} at line ${cause.getStartLine()}, column ${cause.getStartColumn()}: ${cause.getOriginalMessage()}")
            }
        }
        // Double newline as next status update will chomp back one
        term.fg(Color.DEFAULT).newline().newline()
        AnsiConsole.out.print(term.toString())
        AnsiConsole.out.flush()
    }
}
