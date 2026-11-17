package org.ebean.monitor.cli;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "reference", mixinStandardHelpOptions = true,
    description = "Show a comprehensive reference of all commands and options.")
final class ReferenceCommand implements Runnable {

  private static final int MAX_INLINE_SYNOPSIS_OPTIONS = 4;

  @Spec CommandSpec spec;

  @Parameters(index = "0", arity = "0..1", paramLabel = "COMMAND",
      description = "Optional top-level command to filter by (e.g. top, config).")
  @Nullable String command;

  @Override
  public void run() {
    var root = spec.root();
    var groups = uniqueSubcommands(root).stream()
        .filter(subcommand -> subcommand != spec)
        .toList();
    var selectedGroups = selectGroups(groups);
    var out = spec.commandLine().getOut();

    out.println("# " + root.name() + " reference");
    if (command == null || command.isBlank()) {
      var rootDescription = joinDescription(root.usageMessage().description());
      if (!rootDescription.isEmpty()) {
        out.println();
        out.println(rootDescription);
      }
    }

    var selectedLeaves = selectedGroups.stream()
        .flatMap(group -> collectLeaves(group).stream())
        .toList();
    printCommonOptions("Common connection options", ConnectionOptions.class,
        selectedLeaves, selectedGroups, out);
    printCommonOptions("Common output options", OutputOptions.class,
        selectedLeaves, selectedGroups, out);

    for (var group : selectedGroups) {
      printGroup(group, out);
    }
    out.flush();
  }

  private List<CommandSpec> selectGroups(List<CommandSpec> groups) {
    if (command == null || command.isBlank()) {
      return groups;
    }
    var target = command.trim();
    for (var group : groups) {
      if (group.name().equals(target) || Arrays.asList(group.aliases()).contains(target)) {
        return List.of(group);
      }
    }
    var available = groups.stream().map(CommandSpec::name).collect(Collectors.joining(", "));
    throw new CliException("Unknown command '" + target + "'. Available commands: " + available);
  }

  private static void printCommonOptions(
      String heading, Class<?> mixinType, List<CommandSpec> selectedLeaves,
      List<CommandSpec> selectedGroups, PrintWriter out) {
    var commands = selectedLeaves.stream()
        .filter(command -> !mixinOptions(command, mixinType).isEmpty())
        .toList();
    if (commands.isEmpty()) {
      return;
    }

    var sample = commands.getFirst();
    var help = new Help(sample, Help.Ansi.OFF);
    var commonOptions = mixinOptions(sample, mixinType).stream()
        .sorted((left, right) -> left.longestName().compareTo(right.longestName()))
        .map(option -> new Entry(formatOptionLabel(help, option), renderDescription(help, option)))
        .toList();

    var applicableCommands = selectedGroups.stream()
        .filter(group -> collectLeaves(group).stream()
            .anyMatch(leaf -> !mixinOptions(leaf, mixinType).isEmpty()))
        .map(group -> "`" + group.name() + "`")
        .collect(Collectors.joining(", "));

    out.println();
    out.println("## " + heading);
    out.println();
    out.println("Available on: " + applicableCommands);
    out.println();
    printEntries(commonOptions, out);
  }

  private static void printGroup(CommandSpec group, PrintWriter out) {
    out.println();
    out.println("## " + group.qualifiedName(" "));
    var description = joinDescription(group.usageMessage().description());
    if (!description.isEmpty()) {
      out.println();
      out.println(description);
    }

    for (var leaf : collectLeaves(group)) {
      printLeaf(leaf, out);
    }
  }

  private static void printLeaf(CommandSpec leaf, PrintWriter out) {
    var help = new Help(leaf, Help.Ansi.OFF);
    var excludedOptions = sharedMixinOptions(leaf);
    var positionals = leaf.positionalParameters().stream()
        .filter(parameter -> !parameter.hidden())
        .toList();
    var options = leaf.options().stream()
        .filter(option -> !option.hidden() && !option.usageHelp() && !option.versionHelp())
        .filter(option -> !excludedOptions.contains(option))
        .toList();

    out.println();
    out.println("### " + buildSynopsis(leaf, help, positionals, options));

    var description = joinDescription(leaf.usageMessage().description());
    if (!description.isEmpty()) {
      out.println();
      out.println(description);
    }
    if (leaf.aliases().length > 0) {
      var aliases = Arrays.stream(leaf.aliases())
          .map(alias -> "`" + alias + "`")
          .collect(Collectors.joining(", "));
      out.println();
      out.println("Aliases: " + aliases);
    }

    var entries = new ArrayList<Entry>(positionals.size() + options.size());
    for (var parameter : positionals) {
      entries.add(new Entry(renderParameterLabel(help, parameter), renderDescription(help, parameter)));
    }
    for (var option : options) {
      entries.add(new Entry(formatOptionLabel(help, option), renderDescription(help, option)));
    }
    if (!entries.isEmpty()) {
      out.println();
      printEntries(entries, out);
    }
  }

  private static String buildSynopsis(
      CommandSpec command, Help help, List<PositionalParamSpec> positionals, List<OptionSpec> options) {
    var synopsis = new StringBuilder(command.qualifiedName(" "));
    for (var parameter : positionals) {
      synopsis.append(' ').append(renderParameterLabel(help, parameter));
    }
    if (options.size() > MAX_INLINE_SYNOPSIS_OPTIONS) {
      synopsis.append(" [options]");
    } else {
      for (var option : options) {
        var token = option.longestName() + renderParameterLabel(help, option);
        synopsis.append(' ').append(option.required() ? token : "[" + token + "]");
      }
    }
    return synopsis.toString();
  }

  private static void printEntries(List<Entry> entries, PrintWriter out) {
    var labelWidth = entries.stream()
        .mapToInt(entry -> entry.label().length())
        .max()
        .orElse(0);
    for (var entry : entries) {
      if (entry.description().isEmpty()) {
        out.println("  " + entry.label());
      } else {
        var padding = " ".repeat(Math.max(2, labelWidth - entry.label().length() + 2));
        out.println("  " + entry.label() + padding + entry.description());
      }
    }
  }

  private static Set<OptionSpec> sharedMixinOptions(CommandSpec command) {
    var options = new LinkedHashSet<OptionSpec>();
    for (var mixin : command.mixins().values()) {
      var mixinObject = mixin.userObject();
      if (mixinObject instanceof ConnectionOptions || mixinObject instanceof OutputOptions) {
        options.addAll(mixin.options());
      }
    }
    return Set.copyOf(options);
  }

  private static List<OptionSpec> mixinOptions(CommandSpec command, Class<?> mixinType) {
    var options = new ArrayList<OptionSpec>();
    for (var mixin : command.mixins().values()) {
      if (mixinType.isInstance(mixin.userObject())) {
        options.addAll(mixin.options());
      }
    }
    return List.copyOf(options);
  }

  private static List<CommandSpec> collectLeaves(CommandSpec command) {
    var children = uniqueSubcommands(command);
    if (children.isEmpty()) {
      return List.of(command);
    }
    var leaves = new ArrayList<CommandSpec>();
    for (var child : children) {
      leaves.addAll(collectLeaves(child));
    }
    return List.copyOf(leaves);
  }

  private static List<CommandSpec> uniqueSubcommands(CommandSpec command) {
    var unique = new LinkedHashMap<CommandSpec, CommandSpec>();
    for (var subcommand : command.subcommands().values()) {
      var subcommandSpec = subcommand.getCommandSpec();
      if (!subcommandSpec.usageMessage().hidden()) {
        unique.putIfAbsent(subcommandSpec, subcommandSpec);
      }
    }
    return List.copyOf(unique.keySet());
  }

  private static String formatOptionLabel(Help help, OptionSpec option) {
    return String.join(", ", option.names()) + renderParameterLabel(help, option);
  }

  private static String renderParameterLabel(Help help, ArgSpec parameter) {
    return help.parameterLabelRenderer()
        .renderParameterLabel(parameter, Help.Ansi.OFF, List.of())
        .toString();
  }

  private static String renderDescription(Help help, ArgSpec parameter) {
    Help.Ansi.Text[][] rendered;
    if (parameter instanceof OptionSpec option) {
      rendered = help.createDefaultOptionRenderer()
          .render(option, help.parameterLabelRenderer(), help.colorScheme());
    } else if (parameter instanceof PositionalParamSpec positional) {
      rendered = help.createDefaultParameterRenderer()
          .render(positional, help.parameterLabelRenderer(), help.colorScheme());
    } else {
      return joinDescription(parameter.description());
    }
    return Arrays.stream(rendered)
        .filter(row -> row.length > 1)
        .map(row -> row[row.length - 1].plainString().strip())
        .filter(description -> !description.isEmpty())
        .collect(Collectors.joining(" "));
  }

  private static String joinDescription(String[] lines) {
    if (lines == null || lines.length == 0) {
      return "";
    }
    return Arrays.stream(lines)
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .collect(Collectors.joining(" "));
  }

  private record Entry(String label, String description) {
  }
}
