/*
 * Copyright (C) 2010-2013 Geometer Plus <contact@geometerplus.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.geometerplus.android.fbreader;

import java.util.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.util.DisplayMetrics;

import android.util.Log;

import com.abbyy.mobile.lingvo.api.MinicardContract;
import com.paragon.dictionary.fbreader.OpenDictionaryFlyout;
import com.paragon.open.dictionary.api.*;
import com.paragon.open.dictionary.api.Dictionary;
import org.geometerplus.zlibrary.core.filesystem.ZLFile;
import org.geometerplus.zlibrary.core.options.ZLStringOption;
import org.geometerplus.zlibrary.core.resources.ZLResource;
import org.geometerplus.zlibrary.core.xml.ZLXMLReaderAdapter;
import org.geometerplus.zlibrary.core.xml.ZLStringMap;

import org.geometerplus.zlibrary.text.view.ZLTextRegion;
import org.geometerplus.zlibrary.text.view.ZLTextWord;

import org.geometerplus.zlibrary.ui.android.library.ZLAndroidLibrary;

import org.geometerplus.android.util.UIUtil;
import org.geometerplus.android.util.PackageUtil;

public abstract class DictionaryUtil {
	private static int FLAG_INSTALLED_ONLY = 1;
	private static int FLAG_SHOW_AS_DICTIONARY = 2;
	private static int FLAG_SHOW_AS_TRANSLATOR = 4;

	private static ZLStringOption ourSingleWordTranslatorOption;
	private static ZLStringOption ourMultiWordTranslatorOption;
	
	//TODO: maybe langcodes for translator and dictionary could be different
	private static ZLStringOption ourPreferredLanguageOption = new ZLStringOption("Dictionary", "LangCode", "");

	
	public static void setPreferredLanguageCode(String code) {
		ourPreferredLanguageOption.setValue(code);
	}
	
	public static String getPreferredLanguageCode() {
		return ourPreferredLanguageOption.getValue();
	}

	// Map: dictionary info -> mode if package is not installed
	private static Map<PackageInfo,Integer> ourInfos =
		Collections.synchronizedMap(new LinkedHashMap<PackageInfo,Integer>());

	private static class InfoReader extends ZLXMLReaderAdapter {
		@Override
		public boolean dontCacheAttributeValues() {
			return true;
		}

		@Override
		public boolean startElementHandler(String tag, ZLStringMap attributes) {
			if ("dictionary".equals(tag)) {
				final String id = attributes.getValue("id");
				final String title = attributes.getValue("title");
				final String role = attributes.getValue("role");
				int flags;
				if ("dictionary".equals(role)) {
					flags = FLAG_SHOW_AS_DICTIONARY;
				} else if ("translator".equals(role)) {
					flags = FLAG_SHOW_AS_TRANSLATOR;
				} else {
					flags = FLAG_SHOW_AS_DICTIONARY | FLAG_SHOW_AS_TRANSLATOR;
				}
				if (!"always".equals(attributes.getValue("list"))) {
					flags |= FLAG_INSTALLED_ONLY;
				}
				ourInfos.put(new PackageInfo(
					id,
					attributes.getValue("package"),
					attributes.getValue("class"),
					title != null ? title : id,
					attributes.getValue("action"),
					attributes.getValue("dataKey"),
					attributes.getValue("pattern")
                ), flags);
			}
			return false;
		}
	}

	private static class BitKnightsInfoReader extends ZLXMLReaderAdapter {
		private final Context mContext;
		private int mCounter;

		BitKnightsInfoReader(Context context) {
			mContext = context;
		}

		@Override
		public boolean dontCacheAttributeValues() {
			return true;
		}

		@Override
		public boolean startElementHandler(String tag, ZLStringMap attributes) {
			if ("dictionary".equals(tag)) {
				final PackageInfo info = new PackageInfo(
					"BK" + mCounter ++,
					attributes.getValue("package"),
					"com.bitknights.dict.ShareTranslateActivity",
					attributes.getValue("title"),
					Intent.ACTION_VIEW,
					null,
					"%s"
				);
				if (PackageUtil.canBeStarted(mContext, getDictionaryIntent(info, "test"), false)) {
					ourInfos.put(info, FLAG_SHOW_AS_DICTIONARY | FLAG_INSTALLED_ONLY);
				}
			}

			return false;
		}
	}

	private interface ColorDict3 {
		String ACTION = "colordict.intent.action.SEARCH";
		String QUERY = "EXTRA_QUERY";
		String HEIGHT = "EXTRA_HEIGHT";
		String WIDTH = "EXTRA_WIDTH";
		String GRAVITY = "EXTRA_GRAVITY";
		String MARGIN_LEFT = "EXTRA_MARGIN_LEFT";
		String MARGIN_TOP = "EXTRA_MARGIN_TOP";
		String MARGIN_BOTTOM = "EXTRA_MARGIN_BOTTOM";
		String MARGIN_RIGHT = "EXTRA_MARGIN_RIGHT";
		String FULLSCREEN = "EXTRA_FULLSCREEN";
	}

    private static class OpenDictionaryPackageInfo extends PackageInfo {
        final OpenDictionaryFlyout myFlyout;

        OpenDictionaryPackageInfo(Dictionary dictionary) {
            super(dictionary.getUID(),
                  dictionary.getApplicationPackageName(),
                  ".Start",
                  dictionary.getName(),
                  null,
                  null,
                  "%s");
            myFlyout = new OpenDictionaryFlyout(dictionary);
        }
    }

    private static class OpenDictionaryAPIInfoReader {
        static void read(OpenDictionaryAPI api) {
            if (api.getDictionaries().isEmpty()) {
                return;
            }

            SortedSet<Dictionary> dictionariesTreeSet = new TreeSet<Dictionary>(new Comparator<Dictionary>() {
                @Override
                public int compare(Dictionary lhs, Dictionary rhs) {
                    return lhs.toString().compareTo(rhs.toString());
                }
            });

            dictionariesTreeSet.addAll(new ArrayList<Dictionary>(api.getDictionaries()));

            for (Dictionary dict : dictionariesTreeSet) {
                final PackageInfo info = new OpenDictionaryPackageInfo(dict);
                ourInfos.put(info, FLAG_SHOW_AS_DICTIONARY);
            }
        }
    }

	public static void init(final Context context) {
		if (ourInfos.isEmpty()) {
            final OpenDictionaryAPI api = new OpenDictionaryAPI(context);
			final Thread initThread = new Thread(new Runnable() {
				public void run() {
					new InfoReader().readQuietly(ZLFile.createFileByPath("dictionaries/main.xml"));
					new BitKnightsInfoReader(context).readQuietly(ZLFile.createFileByPath("dictionaries/bitknights.xml"));
                    OpenDictionaryAPIInfoReader.read(api);
				}
			});
			initThread.setPriority(Thread.MIN_PRIORITY);
			initThread.start();
		}
	}

	public static List<PackageInfo> dictionaryInfos(Context context, boolean dictionaryNotTranslator) {
		final LinkedList<PackageInfo> list = new LinkedList<PackageInfo>();
		final HashSet<String> installedPackages = new HashSet<String>();
		final HashSet<String> notInstalledPackages = new HashSet<String>();
		synchronized (ourInfos) {
			for (Map.Entry<PackageInfo,Integer> entry : ourInfos.entrySet()) {
				final PackageInfo info = entry.getKey();
				final int flags = entry.getValue();
				if (dictionaryNotTranslator) {
					if ((flags & FLAG_SHOW_AS_DICTIONARY) == 0) {
						continue;
					}
				} else {
					if ((flags & FLAG_SHOW_AS_TRANSLATOR) == 0) {
						continue;
					}
				}
				if (((flags & FLAG_INSTALLED_ONLY) == 0) ||
					installedPackages.contains(info.PackageName)) {
					list.add(info);
				} else if (!notInstalledPackages.contains(info.PackageName)) {
					if (PackageUtil.canBeStarted(context, getDictionaryIntent(info, "test"), false)) {
						list.add(info);
						installedPackages.add(info.PackageName);
					} else {
						notInstalledPackages.add(info.PackageName);
					}
				}
			}
		}
		return list;
	}

	private static PackageInfo firstInfo() {
		synchronized (ourInfos) {
			for (Map.Entry<PackageInfo,Integer> entry : ourInfos.entrySet()) {
				if ((entry.getValue() & FLAG_INSTALLED_ONLY) == 0) {
					return entry.getKey();
				}
			}
		}
		throw new RuntimeException("There are no available dictionary infos");
	}

	public static ZLStringOption singleWordTranslatorOption() {
		if (ourSingleWordTranslatorOption == null) {
			ourSingleWordTranslatorOption = new ZLStringOption("Dictionary", "Id", firstInfo().Id);
		}
		return ourSingleWordTranslatorOption;
	}

	public static ZLStringOption multiWordTranslatorOption() {
		if (ourMultiWordTranslatorOption == null) {
			ourMultiWordTranslatorOption = new ZLStringOption("Translator", "Id", firstInfo().Id);
		}
		return ourMultiWordTranslatorOption;
	}

	private static PackageInfo getCurrentDictionaryInfo(boolean singleWord) {
		final ZLStringOption option = singleWord
			? singleWordTranslatorOption() : multiWordTranslatorOption();
		final String id = option.getValue();
		synchronized (ourInfos) {
			for (PackageInfo info : ourInfos.keySet()) {
				if (info.Id.equals(id)) {
					return info;
				}
			}
		}
		return firstInfo();
	}

	private static Intent getDictionaryIntent(String text, boolean singleWord) {
		return getDictionaryIntent(getCurrentDictionaryInfo(singleWord), text);
	}

	public static Intent getDictionaryIntent(PackageInfo dictionaryInfo, String text) {
		final Intent intent = new Intent(dictionaryInfo.IntentAction);
		if (dictionaryInfo.PackageName != null) {
			String cls = dictionaryInfo.ClassName;
			if (cls != null && cls.startsWith(".")) {
				cls = dictionaryInfo.PackageName + cls;
			}
			intent.setComponent(new ComponentName(
				dictionaryInfo.PackageName, cls
			));
		}
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		text = dictionaryInfo.IntentDataPattern.replace("%s", text);
		if (dictionaryInfo.IntentKey != null) {
			return intent.putExtra(dictionaryInfo.IntentKey, text);
		} else {
			return intent.setData(Uri.parse(text));
		}
	}

    public static class PopupFrameMetric {
        public final int height;
        public final int gravity;
        public final int top;

        PopupFrameMetric(DisplayMetrics metrics, int selectionTop, int selectionBottom) {
            final int screenHeight = metrics.heightPixels;
            final int topSpace = selectionTop;
            final int bottomSpace = metrics.heightPixels - selectionBottom;
            final boolean showAtBottom = bottomSpace >= topSpace;
            final int space = (showAtBottom ? bottomSpace : topSpace) - metrics.densityDpi / 12;
            final int maxHeight = Math.min(metrics.densityDpi * 20 / 12, screenHeight * 2 / 3);
            final int minHeight = Math.min(metrics.densityDpi * 10 / 12, screenHeight * 2 / 3);
            height = Math.max(minHeight, Math.min(maxHeight, space));
            gravity = showAtBottom ? android.view.Gravity.BOTTOM : android.view.Gravity.TOP;
            top = showAtBottom ? metrics.heightPixels - height : 0;
        }
    }

	public static void openTextInDictionary(final Activity activity, String text, boolean singleWord, int selectionTop, int selectionBottom) {
        Log.d("FBReader", "DictionaryUtil:openTextInDictionary");
        if (singleWord) {
			int start = 0;
			int end = text.length();
			for (; start < end && !Character.isLetterOrDigit(text.charAt(start)); ++start);
			for (; start < end && !Character.isLetterOrDigit(text.charAt(end - 1)); --end);
			if (start == end) {
				return;
			}
			text = text.substring(start, end);
		}

        final DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        final PopupFrameMetric frameMetrics = new PopupFrameMetric(metrics, selectionTop, selectionBottom);

		final PackageInfo info = getCurrentDictionaryInfo(singleWord);

        if (info instanceof OpenDictionaryPackageInfo)
        {
            Log.d("FBReader", "DictionaryUtil - work with Open Dictionary API :" + text);
            final OpenDictionaryPackageInfo openDictionary = (OpenDictionaryPackageInfo)info;
            openDictionary.myFlyout.showTranslation(activity, text, frameMetrics);
            return;
        }
        
        if ("ABBYY Lingvo".equals(info.Id)) {
        	final Intent intent = new Intent(MinicardContract.MINICARD_ACTION);
        	intent.putExtra(MinicardContract.EXTRA_TEXT, text);
        	intent.putExtra(MinicardContract.EXTRA_GRAVITY, frameMetrics.gravity);
        	intent.putExtra(MinicardContract.EXTRA_HEIGHT, frameMetrics.height);
        	intent.putExtra(MinicardContract.EXTRA_FORCE_LEMMATIZATION, true);
        	intent.putExtra(MinicardContract.EXTRA_TRANSLATE_VARIANTS, true);
        	intent.putExtra(MinicardContract.EXTRA_LIGHT_THEME, true);
        	if (ourPreferredLanguageOption.getValue() == null || ourPreferredLanguageOption.getValue().equals("")) {
        		
        	} else {
        		intent.putExtra(MinicardContract.EXTRA_LANGUAGE_TO, ourPreferredLanguageOption.getValue());
        	}
        	
        	try {
        		activity.startActivity(intent);
        	} catch (ActivityNotFoundException e) {
        		DictionaryUtil.installDictionaryIfNotInstalled(activity, singleWord, true);
    		}
        	return;
		}

		final Intent intent = getDictionaryIntent(info, text);
		try {
			if ("ColorDict".equals(info.Id)) {
				intent.putExtra(ColorDict3.HEIGHT, frameMetrics.height);
				intent.putExtra(ColorDict3.GRAVITY, frameMetrics.gravity);
				final ZLAndroidLibrary zlibrary = (ZLAndroidLibrary)ZLAndroidLibrary.Instance();
				intent.putExtra(ColorDict3.FULLSCREEN, !zlibrary.ShowStatusBarOption.getValue());
			}
			activity.startActivity(intent);
		} catch (ActivityNotFoundException e) {
			DictionaryUtil.installDictionaryIfNotInstalled(activity, singleWord, false);
		}
	}

	public static void openWordInDictionary(Activity activity, ZLTextWord word, ZLTextRegion region) {
		openTextInDictionary(
			activity, word.toString(), true, region.getTop(), region.getBottom()
		);
	}

	public static void installDictionaryIfNotInstalled(final Activity activity, boolean singleWord, boolean forceNotCheck) {
		if ((!forceNotCheck) &&  PackageUtil.canBeStarted(activity, getDictionaryIntent("test", singleWord), false)) {
			return;
		}
		final PackageInfo dictionaryInfo = getCurrentDictionaryInfo(singleWord);

		final ZLResource dialogResource = ZLResource.resource("dialog");
		final ZLResource buttonResource = dialogResource.getResource("button");
		final ZLResource installResource = dialogResource.getResource("installDictionary");
		new AlertDialog.Builder(activity)
			.setTitle(installResource.getResource("title").getValue())
			.setMessage(installResource.getResource("message").getValue().replace("%s", dictionaryInfo.Title))
			.setIcon(0)
			.setPositiveButton(
				buttonResource.getResource("install").getValue(),
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						installDictionary(activity, dictionaryInfo);
					}
				}
			)
			.setNegativeButton(buttonResource.getResource("skip").getValue(), null)
			.create().show();
	}

	private static void installDictionary(Activity activity, PackageInfo dictionaryInfo) {
		if (!PackageUtil.installFromMarket(activity, dictionaryInfo.PackageName)) {
			UIUtil.showErrorMessage(activity, "cannotRunAndroidMarket", dictionaryInfo.Title);
		}
	}
}
