/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.api.overlay;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OverlayIndex
{
	private static final Set<Integer> overlays = new HashSet<>();

	static
	{
		try (InputStream indexStream = OverlayIndex.class.getResourceAsStream("/runelite/index"))
		{
			if (indexStream == null)
			{
				log.warn("Overlay index resource /runelite/index not found. OverlayIndex will be empty - this is expected in RSPS dev mode.");
			}
			else
			{
				try (DataInputStream in = new DataInputStream(indexStream))
				{
					int id;
					while (true)
					{
						try
						{
							id = in.readInt();
						}
						catch (IOException e)
						{
							break; // EOF reached
						}
						if (id == -1)
						{
							break;
						}
						overlays.add(id);
					}
				}
			}
		}
		catch (Exception ex)
		{
			log.warn("Unable to load overlay index", ex);
		}
	}

	public static boolean hasOverlay(int indexId, int archiveId)
	{
		return overlays.contains(indexId << 16 | archiveId);
	}
}
