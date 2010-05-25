package com.aptana.editor.js.contentassist.index;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;

import com.aptana.core.util.IOUtil;
import com.aptana.editor.common.parsing.IParserPool;
import com.aptana.editor.common.parsing.ParserPoolFactory;
import com.aptana.editor.js.Activator;
import com.aptana.editor.js.IJSConstants;
import com.aptana.editor.js.contentassist.JSASTQueryHelper;
import com.aptana.editor.js.parsing.IJSParserConstants;
import com.aptana.index.core.IFileIndexingParticipant;
import com.aptana.index.core.Index;
import com.aptana.parsing.IParser;
import com.aptana.parsing.ParseState;
import com.aptana.parsing.ast.IParseNode;

public class JSFileIndexingParticipant implements IFileIndexingParticipant
{
	private static final String JS_EXTENSION = "js"; //$NON-NLS-1$

	/*
	 * (non-Javadoc)
	 * @see com.aptana.index.core.IFileIndexingParticipant#index(java.util.Set, com.aptana.index.core.Index,
	 * org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public void index(Set<IFile> files, Index index, IProgressMonitor monitor)
	{
		monitor = SubMonitor.convert(monitor, files.size());
		
		for (IFile file : files)
		{
			if (monitor.isCanceled())
			{
				return;
			}
			
			try
			{
				if (file == null || !isJSFile(file))
				{
					continue;
				}
				
				monitor.subTask(file.getLocation().toPortableString());
				
				try
				{
					// grab the source of the file we're going to parse
					String source = IOUtil.read(file.getContents());

					// minor optimization when creating a new empty file
					if (source != null && source.length() > 0)
					{
						// create parser and associated parse state
						IParserPool pool = ParserPoolFactory.getInstance().getParserPool(IJSParserConstants.LANGUAGE);
						IParser parser = pool.checkOut();
						
						ParseState parseState = new ParseState();
						
						// apply the source to the parse state
						parseState.setEditState(source, source, 0, 0);

						// parse and grab the result
						IParseNode ast = parser.parse(parseState);
						
						pool.checkIn(parser);

						// now walk the parse tree
						this.walkAST(index, file, ast);
					}
				}
				catch (CoreException e)
				{
					Activator.logError(e.getMessage(), e);
				}
				catch (Exception e)
				{
					Activator.logError(e.getMessage(), e);
				}
			}
			finally
			{
				monitor.worked(1);
			}
		}
		
		monitor.done();
	}

	/**
	 * isJSFile
	 * 
	 * @param file
	 * @return
	 */
	private boolean isJSFile(IFile file)
	{
		InputStream stream = null;
		IContentTypeManager manager = Platform.getContentTypeManager();

		try
		{
			stream = file.getContents();

			IContentType[] types = manager.findContentTypesFor(stream, file.getName());

			for (IContentType type : types)
			{
				if (type.getId().equals(IJSConstants.CONTENT_TYPE_JS))
				{
					return true;
				}
			}
		}
		catch (Exception e)
		{
			Activator.logError(e.getMessage(), e);
		}
		finally
		{
			try
			{
				if (stream != null)
				{
					stream.close();
				}
			}
			catch (IOException e)
			{
				// ignore
			}
		}

		return JS_EXTENSION.equalsIgnoreCase(file.getFileExtension());
	}

	/**
	 * walkAST
	 * 
	 * @param index
	 * @param file
	 * @param ast
	 */
	private void walkAST(Index index, IFile file, IParseNode ast)
	{
		JSASTQueryHelper astHelper = new JSASTQueryHelper();
		String location = file.getProjectRelativePath().toPortableString();

		for (String name : astHelper.getGlobalFunctions(ast))
		{
			index.addEntry(JSIndexConstants.FUNCTION, name, location);
		}
		for (String varName : astHelper.getNonFunctionDeclarations(ast))
		{
			index.addEntry(JSIndexConstants.VARIABLE, varName, location);
		}
//		for (String varName : astHelper.getAccidentalGlobals(ast))
//		{
//			System.out.println("accidental global: " + varName);
//			index.addEntry(JSIndexConstants.VARIABLE, varName, location);
//		}
	}
}
