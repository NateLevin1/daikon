package daikon.chicory;

import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Collectors;

// var must be Map<?, ?>
// this will output a synthetic variable var.entrySet()
public class MapInfo extends DaikonVariableInfo {
  public MapInfo(
      String theName, String typeName, String repTypeName, String function_args, boolean isArr) {
    super(theName, typeName, repTypeName, isArr);

    this.function_args = function_args;
  }

  // .getType() variables are derived, so just keep the parent value
  @Override
  public Object getMyValFromParentVal(Object value) {
    return value;
  }

  @Override
  public String getDTraceValueString(Object val) {
    if (isArray) {
      // FIXME
      System.err.println("FIXME: cannot ClassNameInfo#getDTraceValueString an array yet");
      return "nonsensical" + DaikonWriter.lineSep + "2";
    } else {
      if (!(val instanceof Map<?, ?>)) {
        return "nonsensical" + DaikonWriter.lineSep + "2";
      }
      return getValueStringNonArr((Map<?, ?>) val);
    }
  }

  public String getValueStringNonArr(Map<?, ?> val) {
    String valString;

    if (val.size() < 100) {
      valString =
          ("\".size() == " + val.size() + "|.entrySet() == " + val.entrySet() + "\"")
              + DaikonWriter.lineSep
              + "1";
    } else {
      valString =
          ("\".size() == "
                  + val.size()
                  + "|.entrySet() == "
                  + val.entrySet().stream().limit(100).collect(Collectors.toSet())
                  + "...\"")
              + DaikonWriter.lineSep
              + "1";
    }

    return valString;
  }

  /** Returns function since essentially this is a call to a pure function. */
  @Override
  public VarKind get_var_kind() {
    return VarKind.FUNCTION;
  }

  /** Returns the name of this field. */
  @Override
  public String get_relative_name() {
    return "mapInfo";
  }

  @Override
  public EnumSet<VarFlags> get_var_flags() {
    EnumSet<VarFlags> flags = super.get_var_flags();
    flags.add(VarFlags.SYNTHETIC);
    // FIXME: this adds quotes around everything, just so we get the benefit of
    // OneOfString support
    flags.add(VarFlags.TO_STRING);
    flags.add(VarFlags.NON_NULL);
    return flags;
  }
}
