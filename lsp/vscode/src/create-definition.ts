export interface CreateDefinitionChoice {
  label: string;
  content: string;
}

export type CreateDefinitionKind = "Node" | "Media";

export interface CreateDefinitionCommandArgs {
  uri: string;
  kind: CreateDefinitionKind;
  id: string;
  choices: CreateDefinitionChoice[];
}

export interface CreateDefinitionSelection {
  uri: string;
  label: string;
  content: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

export function parseCreateDefinitionCommandArgs(value: unknown): CreateDefinitionCommandArgs | undefined {
  if (!isRecord(value)) return undefined;
  const uri = value.uri;
  const kind = value.kind;
  const id = value.id;
  const rawChoices = value.choices;
  if (
    typeof uri !== "string" ||
    typeof id !== "string" ||
    (kind !== "Node" && kind !== "Media") ||
    !Array.isArray(rawChoices)
  ) {
    return undefined;
  }

  const choices = rawChoices.flatMap((rawChoice): CreateDefinitionChoice[] => {
    if (!isRecord(rawChoice) || typeof rawChoice.label !== "string" || typeof rawChoice.content !== "string") {
      return [];
    }
    return [{ label: rawChoice.label, content: rawChoice.content }];
  });
  if (choices.length === 0) return undefined;

  return { uri, kind, id, choices };
}

export function selectedCreateDefinition(
  args: CreateDefinitionCommandArgs,
  label: string | undefined,
): CreateDefinitionSelection | undefined {
  if (!label) return undefined;
  const choice = args.choices.find((candidate) => candidate.label === label);
  if (!choice) return undefined;
  return { uri: args.uri, label: choice.label, content: choice.content };
}
