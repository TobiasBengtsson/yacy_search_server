// YMarkXBELImporter.java
// (C) 2011 by Stefan Förster, sof@gmx.de, Norderstedt, Germany
// first published 2010 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.data.ymark;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;

import net.yacy.kelondro.logging.Log;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLReaderFactory;

public class YMarkXBELImporter extends DefaultHandler implements Runnable {

	public static enum XBEL {
		NOTHING			(""),
		XBEL			("<xbel"),
		TITLE			("<title"),
		DESC			("<desc"),
		BOOKMARK		("<bookmark"),
		FOLDER			("<folder"),
		SEPARATOR		("<separator"),
		ALIAS			("<alias"),
		INFO			("<info"),
		METADATA		("<metadata");
		
        private static StringBuilder buffer = new StringBuilder(25);;
		private String tag;
		
		private XBEL(String t) {
			this.tag = t;
		}
		public String tag() {
			return this.toString().toLowerCase();
		}
		public String endTag(boolean empty) {
			buffer.setLength(0);
			buffer.append(tag);
			if(empty) {
				buffer.append('/');			
			} else {
				buffer.insert(1, '/');
			}
			buffer.append('>');
			return buffer.toString();
		}
		public String startTag(boolean att) {
			buffer.setLength(0);
			buffer.append(tag);
			if(!att)
				buffer.append('>');
			return buffer.toString();
		}
	}

	private HashMap<String,String> ref;
	private HashMap<String,String> bmk;
	private XBEL outer_state;					// BOOKMARK, FOLDER, NOTHING
    private XBEL inner_state;					// DESC, TITLE, INFO, ALIAS, (METADATA), NOTHING
    private boolean parse_value;
   
	private final HashMap<String,HashMap<String,String>> bmkRef;
	private final HashSet<HashMap<String,String>> aliasRef;
    private final StringBuilder buffer;
	private final StringBuilder folder;
	private final StringBuilder foldersString;
	private final InputSource input;
	private final ArrayBlockingQueue<HashMap<String,String>> bookmarks;
	private final XMLReader xmlReader;
	private final String RootFolder;
    
    public YMarkXBELImporter (final InputStream input, int queueSize, String root) throws SAXException {
        this.bmk = 				null;
        this.RootFolder =		root;
        
        this.buffer = 			new StringBuilder();
    	this.foldersString = 	new StringBuilder(YMarkTables.FOLDER_BUFFER_SIZE);
    	this.folder = 			new StringBuilder(YMarkTables.FOLDER_BUFFER_SIZE);
        
        this.folder.append(this.RootFolder);
        
        this.input = 			new InputSource(input);
        this.bmkRef = 			new HashMap<String,HashMap<String,String>>();
    	this.aliasRef = 		new HashSet<HashMap<String,String>>();
        this.bookmarks = 		new ArrayBlockingQueue<HashMap<String,String>>(queueSize);
        
        this.xmlReader = 		XMLReaderFactory.createXMLReader();
        this.xmlReader.setContentHandler(this);
        this.xmlReader.setFeature("http://xml.org/sax/features/namespace-prefixes", false);
        this.xmlReader.setFeature("http://xml.org/sax/features/namespaces", false);
        this.xmlReader.setFeature("http://xml.org/sax/features/validation", false);
    }
    
    public void run() {
    	try {
        	this.xmlReader.parse(this.input);
        } catch (SAXParseException e) {
            Log.logException(e);	
        } catch (SAXException e) {
            Log.logException(e);
        } catch (IOException e) {
            Log.logException(e);
        } finally {
        	try {
        		Log.logInfo(YMarkTables.BOOKMARKS_LOG, "XBEL Importer inserted poison pill in queue");
				this.bookmarks.put(YMarkTables.POISON);
			} catch (InterruptedException e1) {
			    Log.logException(e1);
			}
        }
    }
    
    public void endDocument() throws SAXException {
    	// put alias references in the bookmark queue to ensure that folders get updated
    	// we do that at endDocument to ensure all referenced bookmarks already exist
    	this.bookmarks.addAll(this.aliasRef);
    	this.aliasRef.clear();
    	this.bmkRef.clear();
    }
    
    public void startElement(final String uri, final String name, String tag, final Attributes atts) throws SAXException {
        YMarkDate date = new YMarkDate();
        if (tag == null) return;
        tag = tag.toLowerCase();              
        if (XBEL.BOOKMARK.tag().equals(tag)) {
            this.bmk = new HashMap<String,String>();            
            this.bmk.put(YMarkTables.BOOKMARK.URL.key(), atts.getValue(uri, YMarkTables.BOOKMARK.URL.xbel_attrb()));
            try {
				date.parseISO8601(atts.getValue(uri, YMarkTables.BOOKMARK.DATE_ADDED.xbel_attrb()));
			} catch (ParseException e) {
				// TODO: exception handling
			}
            this.bmk.put(YMarkTables.BOOKMARK.DATE_ADDED.key(), date.toString());
            try {
				date.parseISO8601(atts.getValue(uri, YMarkTables.BOOKMARK.DATE_VISITED.xbel_attrb()));
            } catch (ParseException e) {
            	// TODO: exception handling
            }
            this.bmk.put(YMarkTables.BOOKMARK.DATE_VISITED.key(), date.toString());
            try {
				date.parseISO8601(atts.getValue(uri, YMarkTables.BOOKMARK.DATE_MODIFIED.xbel_attrb()));
			} catch (ParseException e) {
				// TODO: exception handling
			}
            this.bmk.put(YMarkTables.BOOKMARK.DATE_MODIFIED.key(), date.toString());
            UpdateBmkRef(atts.getValue(uri, "id"), true);
            outer_state = XBEL.BOOKMARK;
            inner_state = XBEL.NOTHING;
            this.parse_value = false;            
        } else if(XBEL.FOLDER.tag().equals(tag)) {
        	this.outer_state = XBEL.FOLDER;
        	this.inner_state = XBEL.NOTHING;
        } else if (XBEL.DESC.tag().equals(tag)) {
            this.inner_state = XBEL.DESC;
        	this.parse_value = true;
        } else if (XBEL.TITLE.tag().equals(tag)) {
        	this.inner_state = XBEL.TITLE;
        	this.parse_value = true;
        } else if (XBEL.INFO.tag().equals(tag)) {
        	this.inner_state = XBEL.INFO;
        	this.parse_value = false;
        } else if (XBEL.METADATA.tag().equals(tag)) {
        	/*
        	atts.getValue(uri, "owner");
        	*/
        } else if (XBEL.ALIAS.tag().equals(tag)) {
        	final String r = atts.getValue(uri, "ref");
        	UpdateBmkRef(r, false);
        	this.aliasRef.add(this.bmkRef.get(r));
        }
        else {
        	this.outer_state = XBEL.NOTHING;
        	this.inner_state = XBEL.NOTHING;
        	this.parse_value = false;
        }
    }

    public void endElement(final String uri, final String name, String tag) {
        if (tag == null) return;
        tag = tag.toLowerCase();
        if(XBEL.BOOKMARK.tag().equals(tag)) {
			// write bookmark
        	if (!this.bmk.isEmpty()) {				
        		this.bmk.put(YMarkTables.BOOKMARK.FOLDERS.key(), this.folder.toString());
        		try {
					this.bookmarks.put(this.bmk);
					bmk = new HashMap<String,String>();
				} catch (InterruptedException e) {
					Log.logException(e);
				}
			}
        	this.outer_state = XBEL.FOLDER;
        } else if (XBEL.FOLDER.tag().equals(tag)) {
        	// go up one folder
            //TODO: get rid of .toString.equals()
        	if(!this.folder.toString().equals(this.RootFolder)) {
        		folder.setLength(folder.lastIndexOf(YMarkUtil.FOLDERS_SEPARATOR));
        	}
        	this.outer_state = XBEL.FOLDER;
        } else if (XBEL.INFO.tag().equals(tag)) {
        	this.inner_state = XBEL.NOTHING;
        } else if (XBEL.METADATA.tag().equals(tag)) {
        	this.inner_state = XBEL.INFO;
        }
    }

    public void characters(final char ch[], final int start, final int length) {
        if (parse_value) {
        	buffer.append(ch, start, length);      	
        	switch(outer_state) {
            	case BOOKMARK:
            		switch(inner_state) {
            			case DESC:            				
            				this.bmk.put(YMarkTables.BOOKMARK.DESC.key(), buffer.toString());
            				break;
            			case TITLE:
            				this.bmk.put(YMarkTables.BOOKMARK.TITLE.key(), buffer.toString());
            				break;
        				case METADATA:	
        					// TODO: handle xbel bookmark metadata
        					break;
            			default:
            				break;		
            		}
            		break;
            	case FOLDER:
            		switch(inner_state) {
	        			case DESC:
	        				break;
	        			case TITLE:
	        				this.folder.append(YMarkUtil.FOLDERS_SEPARATOR);
	        				this.folder.append(this.buffer);
	        				break;
	        			case METADATA:
        					// TODO: handle xbel folder metadata
	        				break;
	        			default:
	        				break;		
            		}
            		break;
            	default:
            		break;
             }
            this.buffer.setLength(0);
            this.parse_value = false;
        }
    }

    public HashMap<String,String> take() {
        try {
            return this.bookmarks.take();
        } catch (InterruptedException e) {
            Log.logException(e);
            return null;
        }
    }
    
    private void UpdateBmkRef(final String id, final boolean url) {
    	this.foldersString.setLength(0);
    	
    	if(this.bmkRef.containsKey(id)) {
        	this.foldersString.append(this.bmkRef.get(id).get(YMarkTables.BOOKMARK.FOLDERS.key()));
        	this.foldersString.append(',');
        	this.ref = this.bmkRef.get(id);
        } else {
            this.ref = new HashMap<String,String>();
        }
    	this.foldersString.append(this.folder);
        if(url)
        	this.ref.put(YMarkTables.BOOKMARK.URL.key(), this.bmk.get(YMarkTables.BOOKMARK.URL.key()));
        this.ref.put(YMarkTables.BOOKMARK.FOLDERS.key(), this.foldersString.toString());
        this.bmkRef.put(id, ref);
    }
}