package com.aptana.editor.html.parsing;

import java.io.IOException;
import java.util.Stack;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.rules.IToken;

import beaver.Scanner.Exception;
import beaver.Symbol;

import com.aptana.editor.common.parsing.IParserPool;
import com.aptana.editor.common.parsing.ParserPoolFactory;
import com.aptana.editor.css.parsing.ICSSParserConstants;
import com.aptana.editor.html.parsing.HTMLTagScanner.TokenType;
import com.aptana.editor.html.parsing.ast.HTMLElementNode;
import com.aptana.editor.html.parsing.ast.HTMLNode;
import com.aptana.editor.html.parsing.ast.HTMLSpecialNode;
import com.aptana.editor.html.parsing.lexer.HTMLTokens;
import com.aptana.editor.js.parsing.IJSParserConstants;
import com.aptana.parsing.IParseState;
import com.aptana.parsing.IParser;
import com.aptana.parsing.ParseState;
import com.aptana.parsing.ast.IParseNode;
import com.aptana.parsing.ast.ParseBaseNode;
import com.aptana.parsing.ast.ParseRootNode;

public class HTMLParser implements IParser
{

	private static final String ATTR_TYPE = "type"; //$NON-NLS-1$
	private static final String ATTR_LANG = "language"; //$NON-NLS-1$

	@SuppressWarnings("nls")
	private static final String[] CSS_VALID_TYPE_ATTR = new String[] { "text/css" };
	@SuppressWarnings("nls")
	private static final String[] JS_VALID_TYPE_ATTR = new String[] { "application/javascript",
			"application/ecmascript", "application/x-javascript", "application/x-ecmascript", "text/javascript",
			"text/ecmascript", "text/jscript" };
	@SuppressWarnings("nls")
	private static final String[] JS_VALID_LANG_ATTR = new String[] { "JavaScript" };

	private HTMLParserScanner fScanner;
	private HTMLParseState fParseState;
	private Stack<IParseNode> fElementStack;
	private HTMLTagScanner fTagScanner;

	private IParseNode fCurrentElement;
	private Symbol fCurrentSymbol;

	public HTMLParser()
	{
		this(new HTMLParserScanner());
	}

	protected HTMLParser(HTMLParserScanner scanner)
	{
		fScanner = scanner;
		fElementStack = new Stack<IParseNode>();
		fTagScanner = new HTMLTagScanner();
	}

	@Override
	public synchronized IParseNode parse(IParseState parseState) throws java.lang.Exception
	{
		fParseState = (HTMLParseState) parseState;
		String source = new String(parseState.getSource());
		fScanner.setSource(source);

		int startingOffset = parseState.getStartingOffset();
		IParseNode root = new ParseRootNode(HTMLNode.LANGUAGE, new HTMLNode[0], startingOffset, startingOffset
				+ source.length());
		parseAll(root);
		parseState.setParseResult(root);

		return root;
	}

	protected void processSymbol(Symbol symbol) throws IOException, Exception
	{
		switch (symbol.getId())
		{
			case HTMLTokens.START_TAG:
				processStartTag();
				break;
			case HTMLTokens.END_TAG:
				processEndTag();
				break;
			case HTMLTokens.STYLE:
				processStyleTag();
				break;
			case HTMLTokens.SCRIPT:
				processScriptTag();
				break;
		}
	}

	protected void processLanguage(String language, short endToken) throws IOException, Exception
	{
		Symbol startTag = fCurrentSymbol;
		advance();

		int start = fCurrentSymbol.getStart();
		int end = start;
		short id = fCurrentSymbol.getId();
		while (id != endToken && id != HTMLTokens.EOF)
		{
			end = fCurrentSymbol.getEnd();
			advance();
			id = fCurrentSymbol.getId();
		}

		IParseNode[] nested;
		IParserPool pool = ParserPoolFactory.getInstance().getParserPool(language);
		IParser parser = pool.checkOut();
		if (parser == null)
		{
			nested = new IParseNode[0];
		}
		else
		{
			nested = getParseResult(parser, start, end);
		}
		pool.checkIn(parser);
		if (fCurrentElement != null)
		{
			HTMLSpecialNode node = new HTMLSpecialNode(startTag, nested, startTag.getStart(), fCurrentSymbol.getEnd());
			parseAttribute(node, startTag.value.toString());
			fCurrentElement.addChild(node);
		}
	}

	protected HTMLElementNode processCurrentTag()
	{
		HTMLElementNode element = new HTMLElementNode(fCurrentSymbol, fCurrentSymbol.getStart(), fCurrentSymbol
				.getEnd());
		parseAttribute(element, fCurrentSymbol.value.toString());
		return element;
	}

	private void parseAll(IParseNode root) throws IOException, Exception
	{
		fElementStack.clear();
		fCurrentElement = root;

		advance();
		while (fCurrentSymbol.getId() != HTMLTokens.EOF)
		{
			processSymbol(fCurrentSymbol);
			advance();
		}
	}

	private void advance() throws IOException, Exception
	{
		fCurrentSymbol = fScanner.nextToken();
	}

	private IParseNode[] getParseResult(IParser parser, int start, int end)
	{
		try
		{
			String text = fScanner.getSource().get(start, end - start + 1);
			ParseState parseState = new ParseState();
			parseState.setEditState(text, text, 0, 0);
			IParseNode node = parser.parse(parseState);
			addOffset(node, start);
			return new IParseNode[] { node };
		}
		catch (java.lang.Exception e)
		{
		}
		return new IParseNode[0];
	}

	private void processStartTag()
	{
		HTMLElementNode element = processCurrentTag();
		// pushes the element onto the stack
		openElement(element);
	}

	private void processEndTag()
	{
		// only closes current element if current lexeme and element have the same tag name
		if (fCurrentElement != null)
		{
			String tagName = HTMLUtils.stripTagEndings(fCurrentSymbol.value.toString());
			if ((fCurrentElement instanceof HTMLElementNode)
					&& ((HTMLElementNode) fCurrentElement).getName().equalsIgnoreCase(tagName))
			{
				// adjusts the ending offset of current element to include the entire block
				((HTMLElementNode) fCurrentElement).setLocation(fCurrentElement.getStartingOffset(), fCurrentSymbol
						.getEnd());
				closeElement();
			}
		}
	}

	private void processStyleTag() throws IOException, Exception
	{
		HTMLElementNode node = processCurrentTag();
		String language = null;
		String type = node.getAttributeValue(ATTR_TYPE);
		if (type == null || isInArray(type, CSS_VALID_TYPE_ATTR))
		{
			language = ICSSParserConstants.LANGUAGE;
		}
		else if (isJavaScript(node))
		{
			language = IJSParserConstants.LANGUAGE;
		}
		processLanguage(language, HTMLTokens.STYLE_END);
	}

	private void processScriptTag() throws IOException, Exception
	{
		HTMLElementNode node = processCurrentTag();
		String language = null;
		String type = node.getAttributeValue(ATTR_TYPE);
		if (type == null || isJavaScript(node))
		{
			language = IJSParserConstants.LANGUAGE;
		}
		processLanguage(language, HTMLTokens.SCRIPT_END);
	}

	private void parseAttribute(HTMLElementNode element, String tag)
	{
		fTagScanner.setRange(new Document(tag), 0, tag.length());
		IToken token;
		Object data;
		String name = null, value = null;
		while (!(token = fTagScanner.nextToken()).isEOF())
		{
			data = token.getData();
			if (data == null)
			{
				continue;
			}

			if (data == TokenType.ATTR_NAME)
			{
				name = tag.substring(fTagScanner.getTokenOffset(), fTagScanner.getTokenOffset()
						+ fTagScanner.getTokenLength());
			}
			else if (data == TokenType.ATTR_VALUE)
			{
				// found a pair
				value = tag.substring(fTagScanner.getTokenOffset(), fTagScanner.getTokenOffset()
						+ fTagScanner.getTokenLength());
				// strips the quotation marks and any surrounding whitespaces
				element.setAttribute(name, value.substring(1, value.length() - 1).trim());
			}
		}
	}

	/**
	 * Pushes the currently active element onto the stack and sets the specified element as the new active element.
	 * 
	 * @param element
	 */
	private void openElement(HTMLElementNode element)
	{
		// adds the new parent as a child of the current parent
		if (fCurrentElement != null)
		{
			fCurrentElement.addChild(element);
		}

		if (fParseState.getCloseTagType(element.getName()) != HTMLTagInfo.END_FORBIDDEN)
		{
			fElementStack.push(fCurrentElement);
			fCurrentElement = element;
		}
	}

	/**
	 * Closes the element that is on the top of the stack.
	 */
	private void closeElement()
	{
		if (fElementStack.size() > 0)
		{
			fCurrentElement = fElementStack.pop();
		}
		else
		{
			fCurrentElement = null;
		}
	}

	private void addOffset(IParseNode node, int offset)
	{
		if (node instanceof ParseBaseNode)
		{
			ParseBaseNode parseNode = (ParseBaseNode) node;
			parseNode.addOffset(offset);
		}
		IParseNode[] children = node.getChildren();
		for (IParseNode child : children)
		{
			addOffset(child, offset);
		}
	}

	private static boolean isJavaScript(HTMLElementNode node)
	{
		String type = node.getAttributeValue(ATTR_TYPE);
		if (isInArray(type, JS_VALID_TYPE_ATTR))
		{
			return true;
		}
		String langAttr = node.getAttributeValue(ATTR_LANG);
		if (langAttr != null && isInArray(langAttr, JS_VALID_LANG_ATTR))
		{
			return true;
		}
		return false;
	}

	private static boolean isInArray(String element, String[] array)
	{
		for (String arrayElement : array)
		{
			if (element.startsWith(arrayElement))
			{
				return true;
			}
		}
		return false;
	}
}
