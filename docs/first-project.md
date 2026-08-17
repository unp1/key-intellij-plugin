# A first project

This walks from an ordinary Java project to a closed proof. It takes a few minutes, and
nothing in it is specific to the example.

## 1. A method with a contract

KeY proves what a JML contract states. Write one above a method, in a comment that starts
with `@`:

```java
package com.example.core;

public class Account {

    private int balance;

    /*@ public normal_behavior
      @  requires amount > 0;
      @  requires balance + amount <= Integer.MAX_VALUE;
      @  ensures balance == \old(balance) + amount;
      @  assignable balance;
      @*/
    public void deposit(int amount) {
        balance += amount;
    }
}
```

`requires` is what the caller has to establish, `ensures` what the method promises, and
`assignable` what it may change. A method with no contract has nothing to prove and does
not appear in the KeY views.

## 2. Tell KeY which sources to read

KeY reads a source directory of its own, which need not be the whole project. That is
called a **context**.

Open **Settings** → **Tools** → **KeY** → **KeY Contexts**, press **+**, and fill in:

- **Id** — a name of your choosing, for example `core`
- **Java source** — the directory holding the packages, for example `core/src/main/java`
- **Classpath**, **Bootclasspath**, **Includes** — leave empty to start with

Press **Apply**. The paths are checked at once, and a path that KeY would refuse is
reported on the field it belongs to. What you entered is written to `.key/settings.json` in
the project, which is meant to be committed with it.

!!! tip
    A project with several modules gets one context per module. Each is loaded on its own,
    so a broken module does not stop the others.

## 3. Verify

Three ways, all the same work:

- put the caret in `deposit`, right-click, **Verify Proof Obligations**
- open the **KeY** tool window on the right, find the method in **Proof Obligations**, and
  use the same action from its context menu
- press the mark in the gutter beside the method and choose the action from the popup

A progress bar appears, and can be stopped. When the run ends, the notification says how
many obligations were proved, and the gutter mark beside `deposit` turns into a green
check.

## 4. Look at what happened

- The **Verification** tab lists what each attempt measured: the state, the number of proof
  nodes and branches, and how long it took.
- The **Proof Obligations** tab lists everything the project can be asked to prove, with
  KeY's own status icon per row.
- The proof itself is written to `proofs/core/com/example/core/…​.proof`. It is a text
  file, and committing it is what lets a colleague, or a later you, replay the proof
  instead of making it again.

## 5. Change the code and watch

Edit the method so it no longer satisfies the contract, and save:

```java
balance += amount + 1;
```

**Verify on save** is on by default. The saved proof is replayed against the new sources,
does not close any more, and is attempted again; the notification says what broke and the
gutter mark turns into a red cross. Put the method back and save again to see it close.

## 6. Proofs that rest on other proofs

A method that calls another is proved against the *contract* of the one it calls, not its
body. Until that other contract has a closed proof of its own, KeY calls the first proof
**closed but for lemmas**, and the gutter shows an orange check in brackets.

Select such a row and use **Show Dependencies** to see what it rests on, then **Verify
Dependencies** to prove those. When the last one closes, the mark turns green by itself.

## Where things live

| | |
|---|---|
| `.key/settings.json` | the contexts and the proof options; commit it |
| `proofs/` | the saved proofs; commit them |
| `proofs/.trash/` | proofs a rerun replaced; see the trash policy in [Settings](settings.md) |
| `.key/tool/` | the KeY the plugin starts keeps its settings, logs and caches here; do not commit it |
