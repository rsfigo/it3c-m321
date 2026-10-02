package ch.benedict.m321.batchwriter.service;

/**
 * Eine Nachricht ist in sich kaputt: kein gültiges JSON, ein Pflichtfeld
 * fehlt oder hat den falschen Typ.
 *
 * Bewusst eine geprüfte Ausnahme: wer liest, MUSS entscheiden, was mit der
 * kaputten Nachricht geschieht. Ein Weiterversuch hilft hier nie, das
 * Ergebnis wäre jedes Mal dasselbe (Spezifikation, Abschnitt 3.7).
 */
public class InvalidMessageException extends Exception {

    /** Hält den Grund fest, damit er im Log und im Header der Dead-Letter-Nachricht steht. */
    public InvalidMessageException(String reason) {
        super(reason);
    }
}
