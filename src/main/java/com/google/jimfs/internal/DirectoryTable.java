/*
 * Copyright 2013 Google Inc.
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

package com.google.jimfs.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.jimfs.internal.Name.PARENT;
import static com.google.jimfs.internal.Name.SELF;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A table of directory entries which link names to {@linkplain File files}.
 *
 * @author Colin Decker
 */
final class DirectoryTable implements FileContent {

  private static final ImmutableSet<Name> RESERVED_NAMES = ImmutableSet.of(SELF, PARENT);

  private final Map<Name, DirEntry> entries = new HashMap<>();

  /**
   * Creates a copy of this table. The copy does <i>not</i> contain a copy of the entries in this
   * table.
   */
  @Override
  public DirectoryTable copy() {
    return new DirectoryTable();
  }

  @Override
  public int sizeInBytes() {
    return 0;
  }

  /**
   * Returns the file for this directory.
   */
  public File self() {
    return get(SELF);
  }

  /**
   * Returns the parent directory.
   */
  public File parent() {
    return get(PARENT);
  }

  /**
   * Returns the directory table for the parent directory.
   */
  public DirectoryTable parentTable() {
    return entries.get(PARENT).file.content();
  }

  /**
   * Returns the current name of this directory. This relies on the fact that multiple links to a
   * directory can't be created, not counting special self and parent links which don't apply
   * here. Note that this method cannot be used to get the name of a root directory, as its parent
   * link is to itself.
   */
  public Name name() {
    return parentTable().getName(self());
  }

  /**
   * Links this directory to its own file.
   */
  public void linkSelf(File self) {
    linkInternal(SELF, checkNotNull(self));
  }

  /**
   * Links this directory to the given parent file.
   */
  public void linkParent(File parent) {
    linkInternal(PARENT, checkNotNull(parent));
  }

  /**
   * Unlinks this directory from its own file.
   */
  public void unlinkSelf() {
    unlinkInternal(SELF);
  }

  /**
   * Unlinks this directory from its parent file.
   */
  public void unlinkParent() {
    unlinkInternal(PARENT);
  }

  public int size() {
    return entries.size();
  }

  /**
   * Returns true if this directory has no entries other than those to itself and its parent.
   */
  public boolean isEmpty() {
    return entries.size() == 2;
  }

  /**
   * Links the given name to the given file in this table.
   *
   * @throws IllegalArgumentException if {@code name} is a reserved name such as "." or if an
   *     entry already exists for the it
   */
  public void link(Name name, File file) {
    linkInternal(checkValidName(name, "link"), file);
  }

  private void linkInternal(Name name, File file) {
    checkArgument(!entries.containsKey(name), "entry '%s' already exists", name);
    entries.put(name, new DirEntry(name, file));
    file.linked();
  }

  /**
   * Unlinks the given name from any key it is linked to in this table. Returns the file key that
   * was linked to the name, or {@code null} if no such mapping was present.
   *
   * @throws IllegalArgumentException if {@code name} is a reserved name such as "."
   */
  public void unlink(Name name) {
    unlinkInternal(checkValidName(name, "unlink"));
  }

  private void unlinkInternal(Name name) {
    DirEntry entry = entries.remove(name);
    if (entry == null) {
      throw new IllegalArgumentException("no entry matching '" + name + "' in this directory");
    }
    entry.file.unlinked();
  }

  /**
   * Returns the entry for the file linked by the given name in this directory or {@code null} if
   * no such file exists.
   */
  @Nullable
  public DirEntry getEntry(Name name) {
    return entries.get(name);
  }

  /**
   * Returns the file linked by the given name in this directory or {@code null} if no such file
   * exists.
   */
  @Nullable
  public File get(Name name) {
    DirEntry entry = entries.get(name);
    return entry == null ? null : entry.file;
  }

  /**
   * Returns the canonical form of the given name in this directory.
   *
   * @throws IllegalArgumentException if the table does not contain an entry with the given name
   */
  public Name canonicalize(Name name) {
    DirEntry entry = entries.get(name);
    if (entry == null) {
      throw new IllegalArgumentException("no entry matching '" + name + "' in this directory");
    }
    return entry.name;
  }

  /**
   * Returns the name that links to the given file key in this directory, throwing an exception if
   * zero names or more than one name links to the key. Should only be used for getting the name of
   * a directory, as directories cannot have more than one link.
   */
  public Name getName(File file) {
    Name result = null;
    for (Map.Entry<Name, DirEntry> entry : entries.entrySet()) {
      Name name = entry.getKey();
      DirEntry dirEntry = entry.getValue();
      if (dirEntry.file.equals(file)) {
        if (result == null) {
          result = name;
        } else {
          throw new IllegalArgumentException("more than one name links to the given file");
        }
      }
    }

    if (result == null) {
      throw new IllegalArgumentException("directory contains no links to the given file");
    }

    return result;
  }

  /**
   * Creates an immutable sorted snapshot of the names this directory contains, excluding
   * "." and "..".
   */
  public ImmutableSortedSet<Name> snapshot() {
    return ImmutableSortedSet.copyOf(Ordering.usingToString(), asMap().keySet());
  }

  private Map<Name, DirEntry> asMap() {
    return Maps.filterKeys(entries, Predicates.not(Predicates.in(RESERVED_NAMES)));
  }

  /**
   * Returns a view of the entries in this table, excluding entries for "." and "..".
   */
  public Collection<DirEntry> entries() {
    return asMap().values();
  }

  private static Name checkValidName(Name name, String action) {
    checkArgument(!RESERVED_NAMES.contains(name), "cannot %s: %s", action, name);
    return name;
  }

  /**
   * Directory entry containing a file and a name linking to that file.
   */
  public static final class DirEntry {

    private final Name name;
    private final File file;

    private DirEntry(Name name, File file) {
      this.name = name;
      this.file = file;
    }

    /**
     * Returns the name of this entry.
     */
    public Name name() {
      return name;
    }

    /**
     * Returns the file this entry links to.
     */
    public File file() {
      return file;
    }
  }
}
