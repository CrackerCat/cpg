/*
 * Copyright (c) 2019, Fraunhofer AISEC. All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *                    $$$$$$\  $$$$$$$\   $$$$$$\
 *                   $$  __$$\ $$  __$$\ $$  __$$\
 *                   $$ /  \__|$$ |  $$ |$$ /  \__|
 *                   $$ |      $$$$$$$  |$$ |$$$$\
 *                   $$ |      $$  ____/ $$ |\_$$ |
 *                   $$ |  $$\ $$ |      $$ |  $$ |
 *                   \$$$$$   |$$ |      \$$$$$   |
 *                    \______/ \__|       \______/
 *
 */

package de.fraunhofer.aisec.cpg.graph.type;

import de.fraunhofer.aisec.cpg.graph.TypeManager;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class responsible for parsing the type definition and create the same Type as described by the
 * type string, but complying to the CPG TypeSystem
 */
public class TypeParser {

  public static final String UNKNOWN_TYPE_STRING = "UNKNOWN";
  private static final List<String> primitives =
      List.of("byte", "short", "int", "long", "float", "double", "boolean", "char");
  private static final Pattern TYPE_FROM_STRING =
      Pattern.compile(
          "(?:(?<modifier>[a-zA-Z]*) )?(?<type>[a-zA-Z0-9_$.<>]*)(?<adjustment>[\\[\\]*&\\s]*)?");
  private static final Pattern functionPtrRegex =
      Pattern.compile(
          "(?:(?<functionptr>(\\h|\\()+[a-zA-Z0-9_$.<>:]*\\*\\h*[a-zA-Z0-9_$.<>:]*(\\h|\\))+)\\h*)(?<args>\\(+[a-zA-Z0-9_$.<>,\\h]*\\))");

  private static TypeManager.Language language = TypeManager.getInstance().getLanguage();

  /**
   * WARNING: This is only intended for Test Purposes of the TypeParser itself without parsing
   * files. Do not use this.
   *
   * @param language
   */
  public static void setLanguage(TypeManager.Language language) {
    TypeParser.language = language;
  }

  public static TypeManager.Language getLanguage() {
    return language;
  }

  // TODO needed?
  private static String clean(String type) {
    if (type.contains("?")
        || type.contains("org.eclipse.cdt.internal.core.dom.parser.ProblemType@")) {
      return UNKNOWN_TYPE_STRING;
    }
    type = type.replaceAll("^struct ", "");
    type = type.replaceAll("^const struct ", "");
    type = type.replaceAll(" const ", " ");
    // remove artifacts from unidentified C++ namespaces
    type = type.replaceAll("\\{.*}::", "");
    // remove irrelevant array sizes cluttering the type name
    type = type.replaceAll("\\[[ \\d]*]", "[]");
    // remove function signature info
    type = type.replaceAll("\\(.*\\)", "");
    // unify separator
    type = type.replace("::", ".");
    return type.strip();
  }

  /**
   * Infers corresponding qualifier information for the type depending of the keywords.
   *
   * @param typeString list of found keywords
   * @param old previous qualifier information which is completed with newer qualifier information
   * @return Type.Qualifier
   */
  public static Type.Qualifier calcQualifier(List<String> typeString, Type.Qualifier old) {
    boolean constant_flag = false;
    boolean volatile_flag = false;
    boolean restrict_flag = false;
    boolean atomic_flag = false;

    if (old != null) {
      constant_flag = old.isConst();
      volatile_flag = old.isVolatile();
      restrict_flag = old.isRestrict();
      atomic_flag = old.isAtomic();
    }

    for (String part : typeString) {
      switch (part) {
        case "final":
        case "const":
          constant_flag = true;
          break;

        case "volatile":
          volatile_flag = true;
          break;

        case "restrict":
          restrict_flag = true;
          break;

        case "atomic":
          atomic_flag = true;
          break;
      }
    }

    return new Type.Qualifier(constant_flag, volatile_flag, restrict_flag, atomic_flag);
  }

  /**
   * Infers the corresponding storage type depending on the present storage keyword. Default AUTO
   *
   * @param typeString List of storage keywords
   * @return Storage
   */
  public static Type.Storage calcStorage(List<String> typeString) {
    for (String part : typeString) {
      try {
        return Type.Storage.valueOf(part.toUpperCase());
      } catch (IllegalArgumentException e) {
        continue;
      }
    }
    return Type.Storage.AUTO;
  }

  public static boolean isStorageSpecifier(String specifier) {
    if (getLanguage() == TypeManager.Language.CXX) {
      return specifier.toUpperCase().equals("STATIC");
    } else {
      try {
        Type.Storage.valueOf(specifier.toUpperCase());
        return true;
      } catch (IllegalArgumentException e) {
        return false;
      }
    }
  }

  protected static boolean isQualifierSpecifier(String qualifier) {
    if (getLanguage() == TypeManager.Language.JAVA) {
      return qualifier.equals("final") || qualifier.equals("volatile");
    } else {
      return qualifier.equals("const")
          || qualifier.equals("volatile")
          || qualifier.equals("restrict")
          || qualifier.equals("atomic");
    }
  }

  public static boolean isKnownSpecifier(String specifier) {
    return isQualifierSpecifier(specifier) || isStorageSpecifier(specifier);
  }

  /**
   * searching closing bracket
   *
   * @param openBracket opening bracket char
   * @param closeBracket closing bracket char
   * @param string substring without the openening bracket
   * @return position of the closing bracket
   */
  private static int findMatching(char openBracket, char closeBracket, String string) {
    int counter = 1;
    int i = 0;

    while (counter != 0) {
      char actualChar = string.charAt(i);
      if (actualChar == openBracket) {
        counter++;
      } else if (actualChar == closeBracket) {
        counter--;
      }
      i++;
    }

    return i;
  }

  /**
   * Matches the type blocks and checks if it the typeString has the structure of a function pointer
   *
   * @param type separated type string
   * @return true if function pointer structure is found in typeString, false if not
   */
  private static Matcher isFunctionPointer(List<String> type) {

    StringBuilder typeStringBuilder = new StringBuilder();
    for (String typePart : type) {
      typeStringBuilder.append(typePart);
    }

    String typeString = typeStringBuilder.toString().strip();

    Matcher matcher = functionPtrRegex.matcher(typeString);
    if (matcher.find()) {
      return matcher;
    }
    return null;
  }

  private static boolean isIncompleteType(String typeName) {
    return typeName.strip().equals("void");
  }

  private static boolean isUnknownType(String typeName) {
    return typeName.toUpperCase().contains("UNKNOWN");
  }

  /**
   * Removes spaces between a generics Expression, i.e. between "<" and ">" and the preceding Type
   * information Required since, afterwards typeString get splitted by spaces
   *
   * @param type typeString
   * @return typeString without spaces in the generic Expression
   */
  private static String fixGenerics(String type) {
    StringBuilder out = new StringBuilder();
    int bracket_count = 0;
    boolean bracketCountStarted = false;
    for (int i = 0; i < type.length(); i++) {
      if (type.charAt(i) == '<') {
        bracket_count++;
        out.append(type.charAt(i));
        i++;
        while (bracket_count > 0) {
          bracketCountStarted = true;
          if (type.charAt(i) == '>') {
            out.append('>');
            bracket_count--;
          } else if (type.charAt(i) == '<') {
            out.append('<');
            bracket_count++;

          } else {
            if (type.charAt(i) != ' ') {
              out.append(type.charAt(i));
            }
          }
          i++;
        }
      } else {
        out.append(type.charAt(i));
      }

      if (bracketCountStarted) {
        bracketCountStarted = false;
        i--;
      }
    }

    String[] splitted = out.toString().split("\\<");
    StringBuilder out2 = new StringBuilder();
    for (int i = 0; i < splitted.length; i++) {
      if (out2.length() > 0) {
        out2.append('<');
      }
      out2.append(splitted[i].trim());
    }

    return out2.toString();
  }

  /**
   * Separates typeString into the different Parts that make up the type information
   *
   * @param type
   * @return
   */
  public static List<String> separate(String type) {

    // Remove :: CPP operator, use . instead
    type = type.replace("::", ".");
    type = type.split("=")[0];

    // Guarantee that there is no arbitraty number of whitespces
    String[] typeSubpart = type.split(" ");

    StringBuilder typeBuilder = new StringBuilder();
    for (String part : typeSubpart) {
      if (!part.equals("")) {
        typeBuilder.append(part).append(" ");
      }
    }
    type = typeBuilder.toString().strip();
    List<String> typeBlocks = new ArrayList<>();

    // Splits TypeString into relevant information blocks
    int lastSplit = 0;
    int finishPosition = 0;
    String substr = "";

    for (int i = 0; i < type.length(); i++) {
      char ch = type.charAt(i);
      switch (ch) {
        case ' ':
          // handle space create element
          substr = type.substring(lastSplit, i);
          if (substr.length() != 0) {
            typeBlocks.add(substr);
          }
          lastSplit = i + 1;
          break;

        case '(':
          // handle ( find matching closing ignore content (not relevant type information)
          substr = type.substring(lastSplit, i);
          if (substr.length() != 0) {
            typeBlocks.add(substr);
          }
          finishPosition = findMatching('(', ')', type.substring(i + 1));
          typeBlocks.add(type.substring(i, i + finishPosition + 1));
          i = finishPosition + i;
          lastSplit = i + 1;
          break;

        case '[':
          // handle [ find matching closing ignore content (not relevant type information)
          substr = type.substring(lastSplit, i);
          if (substr.length() != 0) {
            typeBlocks.add(substr);
          }

          finishPosition = findMatching('[', ']', type.substring(i + 1));
          typeBlocks.add("[]"); // type.substring(i, i+finishPosition+1)
          i = finishPosition + i;
          lastSplit = i + 1;
          break;

        case '*':
          // handle * operator
          substr = type.substring(lastSplit, i);
          if (substr.length() != 0) {
            typeBlocks.add(substr);
          }

          typeBlocks.add("*");
          lastSplit = i + 1;
          break;

        case '&':
          // handle & operator
          substr = type.substring(lastSplit, i);
          if (substr.length() != 0) {
            typeBlocks.add(substr);
          }

          typeBlocks.add("&");
          lastSplit = i + 1;
          break;

        default:
          // everything else
          substr = type.substring(lastSplit, type.length());
          if (substr.length() != 0 && i == type.length() - 1) {
            typeBlocks.add(substr);
          }
          break;
      }
    }

    return typeBlocks;
  }

  private static List<Type> getParameterList(String parameterList) {
    if (parameterList.startsWith("(") && parameterList.endsWith(")")) {
      parameterList = parameterList.strip().substring(1, parameterList.strip().length() - 1);
    }
    List<Type> parameters = new ArrayList<>();
    String[] parametersSplit = parameterList.split(",");
    for (String parameter : parametersSplit) {
      parameters.add(createFrom(parameter.strip()));
    }

    return parameters;
  }

  private static List<Type> getGenerics(String typeName) {
    if (typeName.contains("<") && typeName.contains(">")) {
      String generics = typeName.substring(typeName.indexOf('<') + 1, typeName.lastIndexOf('>'));
      List<Type> genericList = new ArrayList<>();
      String[] parametersSplit = generics.split(",");
      for (String parameter : parametersSplit) {
        genericList.add(createFrom(parameter.strip()));
      }

      return genericList;
    }
    return new ArrayList<>();
  }

  /**
   * Makes sure to apply Expressions containing brackets that change the binding of operators e.g.
   * () can change the binding order of operators
   *
   * @param finalType Modifications are applyed to this type which is the result of the preceding
   *     type calculations
   * @param bracketExpressions List of Strings containing bracket expressions
   * @return modified finalType performing the resolution of the bracket expressions
   */
  private static Type resolveBracketExpression(Type finalType, List<String> bracketExpressions) {
    for (String bracketExpression : bracketExpressions) {
      List<String> splitExpression =
          separate(bracketExpression.substring(1, bracketExpression.length() - 1));
      for (String part : splitExpression) {
        if (part.equals("*")) {
          finalType = finalType.reference();
        }

        if (part.equals("&")) {
          finalType = finalType.dereference();
        }

        if (part.startsWith("[") && part.endsWith("]")) {
          finalType = finalType.reference();
        }
        if (part.startsWith("(") && part.endsWith(")")) {
          List<String> subBracketExpression = new ArrayList<>();
          subBracketExpression.add(part);
          finalType = resolveBracketExpression(finalType, subBracketExpression);
        }
        if (isKnownSpecifier(part)) {
          if (isStorageSpecifier(part)) {
            List<String> specifiers = new ArrayList<>();
            specifiers.add(part);
            finalType.setStorage(calcStorage(specifiers));
          } else {
            List<String> qualifiers = new ArrayList<>();
            qualifiers.add(part);
            finalType.setQualifier(calcQualifier(qualifiers, finalType.getQualifier()));
          }
        }
      }
      return finalType;
    }

    return finalType;
  }

  /**
   * Help function that removes access modifier from the typeString
   *
   * @param type provided typeString
   * @return typeString without access modifier
   */
  private static String clear(String type) {
    return type.replaceAll("public|private|protected", "").strip();
  }

  /**
   * Checks if the List of separated parts of the typeString contains an element indicating that it
   * is a primitive type
   *
   * @param stringList
   * @return
   */
  private static boolean isPrimitiveType(List<String> stringList) {
    for (String s : stringList) {
      if (primitives.contains(s)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Joins compound primitive data types such as long long int and consolidates those information
   * blocks
   *
   * @param typeBlocks
   * @return separated words of compound types are joined into one string
   */
  private static List<String> joinPrimitive(List<String> typeBlocks) {
    List<String> joinedTypeBlocks = new ArrayList<>();
    StringBuilder primitiveType = new StringBuilder();
    boolean foundPrimitive = false;

    for (String s : typeBlocks) {
      if (primitives.contains(s)) {
        if (primitiveType.length() > 0) {
          primitiveType.append(" ");
        }
        primitiveType.append(s);
      }
    }

    for (String s : typeBlocks) {
      if (primitives.contains(s) && !foundPrimitive) {
        joinedTypeBlocks.add(primitiveType.toString());
        foundPrimitive = true;
      } else {
        if (!primitives.contains(s)) {
          joinedTypeBlocks.add(s);
        }
      }
    }

    return joinedTypeBlocks;
  }

  /**
   * Use this function for parsing new types and obtaining a new Type Parser creates from a
   *
   * @param type String the corresponding
   * @return Type
   */
  public static Type createFrom(String type) {
    // Check if Problems during Parsing
    if (type.contains("?")
        || type.contains("org.eclipse.cdt.internal.core.dom.parser.ProblemType@")
        || type.length() == 0) {
      return UnknownType.getUnknownType();
    }

    // Preprocessing of the typeString
    type = clear(type);
    type = fixGenerics(type);

    // Separate typeString into a List containing each part of the typeString
    List<String> typeBlocks = separate(type);

    // Depending if the Type is primitive or not signed/unsigned must be set differently (only
    // relevant for ObjectTypes)
    boolean primitiveType = isPrimitiveType(typeBlocks);

    // Default is signed, unless unsigned keyword is specified. For other classes that are not
    // primitive this is NOT_APPLICABLE
    ObjectType.Modifier modifier = ObjectType.Modifier.NOT_APPLICABLE;
    if (primitiveType) {
      if (typeBlocks.contains("unsigned")) {
        modifier = ObjectType.Modifier.UNSIGNED;
        typeBlocks.remove("unsigned");
      } else {
        modifier = ObjectType.Modifier.SIGNED;
        typeBlocks.remove("signed");
      }
    }

    // Join compound primitive types into one block i.e. types consisting of more than one word e.g.
    // long long int (only primitive types)
    typeBlocks = joinPrimitive(typeBlocks);

    List<String> qualifierList = new ArrayList<>();
    List<String> storageList = new ArrayList<>();

    // Handle preceeding qualifier or storage specifier to the type name e.g. static const int
    int counter = 0;
    for (String part : typeBlocks) {
      if (isKnownSpecifier(part)) {
        if (isQualifierSpecifier(part)) {
          qualifierList.add(part);
        } else if (isStorageSpecifier(part)) {
          storageList.add(part);
        }
        counter++;
      } else {
        break;
      }
    }

    Type.Storage storageValue = calcStorage(storageList);
    Type.Qualifier qualifier = calcQualifier(qualifierList, null);

    // Once all preceeding known keywords (if any) are handled the next word must be the TypeName
    String typeName = typeBlocks.get(counter);
    counter++;

    Type finalType;
    TypeManager typeManager = TypeManager.getInstance();

    // Check if type is FunctionPointer
    Matcher funcptr = isFunctionPointer(typeBlocks.subList(counter, typeBlocks.size()));

    if (funcptr != null) {
      Type returnType = createFrom(typeName);
      List<Type> parameterList = getParameterList(funcptr.group("args"));

      return typeManager.registerType(
          new FunctionPointerType(qualifier, storageValue, parameterList, returnType));
    } else {
      if (isIncompleteType(typeName)) {
        // IncompleteType e.g. void
        finalType = new IncompleteType();
      } else if (isUnknownType(typeName)) {
        // UnknownType -> no information on how to process this type
        finalType = new UnknownType(typeName);
      } else {
        // ObjectType
        // Obtain possible generic List from TypeString
        List<Type> generics = getGenerics(typeName);
        if (typeName.contains("<") && typeName.contains(">")) {
          typeName = typeName.substring(0, typeName.indexOf("<"));
        }
        finalType =
            new ObjectType(typeName, storageValue, qualifier, generics, modifier, primitiveType);
        if (finalType.getTypeName().equals("auto")) {
          // In C++17 if auto keyword is used the compiler infers the type automatically, hence we
          // are not able to find out, which type this should be, it will be resolved due to
          // dataflow
          return UnknownType.getUnknownType();
        }
      }
    }

    // Process Keywords / Operators (*, &) after typeName
    List<String> subPart = typeBlocks.subList(counter, typeBlocks.size());

    List<String> bracketExpressions = new ArrayList<>();

    for (String part : subPart) {

      if (part.equals("*")) {
        // Creates a Pointer to the finalType
        finalType = finalType.reference();
      }

      if (part.equals("&")) {
        // CPP ReferenceTypes are indicated by an & at the end of the typeName e.g. int&, and are
        // handled differently to a pointer
        Type.Qualifier oldQualifier = finalType.getQualifier();
        Type.Storage oldStorage = finalType.getStorage();
        finalType.setQualifier(new Type.Qualifier());
        finalType.setStorage(Type.Storage.AUTO);
        finalType = new ReferenceType(finalType);
        finalType.setStorage(oldStorage);
        finalType.setQualifier(oldQualifier);
      }

      if (part.startsWith("[") && part.endsWith("]")) {
        // Arrays are equal to pointer, create a reference
        finalType = finalType.reference();
      }

      if (part.startsWith("(") && part.endsWith(")")) {
        // BracketExpressions change the binding of operators they are stored in order to be
        // processed afterwards
        bracketExpressions.add(part);
      }

      // Check storage and qualifiers specifierd that are defined after the typeName e.g. int const
      if (isKnownSpecifier(part)) {
        if (isStorageSpecifier(part)) {
          List<String> specifiers = new ArrayList<>();
          specifiers.add(part);
          finalType.setStorage(calcStorage(specifiers));
        } else if (isQualifierSpecifier(part)) {
          List<String> qualifiers = new ArrayList<>();
          qualifiers.add(part);
          finalType.setQualifier(calcQualifier(qualifiers, finalType.getQualifier()));
        }
      }
    }

    // Resolve BracketExpressions that were identified previously
    finalType = resolveBracketExpression(finalType, bracketExpressions);

    // Make sure, that only one real instance exists for a type in order to have just one node in
    // the graph representing the type
    finalType = typeManager.registerType(finalType);

    return finalType;
  }
}